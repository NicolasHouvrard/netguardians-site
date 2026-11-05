package com.logicielpourtous.site.features.licence;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Client HTTP sortant vers le MAITRE, utilise par un serveur {@code PARTNER} deja enrole pour
 * generer un jeton d'installation CLIENT ({@code POST /api/licence/jetons}, Task 4), et par {@code
 * LicenceDecouverteScheduler} (Task 6) pour sonder les nouveaux clients enroles ({@code GET
 * /api/licence/nouveaux-serveurs}) et leur pousser un code local ({@code POST
 * /api/licence/code-partenaire}). DISTINCT de
 * {@code features.sync.RestSyncClient} : URL (propriete) et en-tete d'authentification differents,
 * usage different (generation de jeton, pas de synchronisation de donnees metier) — volontairement
 * pas fusionne ni rendu dependant l'un de l'autre.
 *
 * <p>Actif uniquement si {@code app.licence.master-url} est configure (meme garde que {@link
 * LicenceBootstrap}) : une instance {@code MASTER} n'a besoin d'aucune config {@code app.licence.*}
 * et ce bean n'existe alors meme pas.
 *
 * <p>Authentifie par {@link EtatLicenceLocale#getCleApiLicence()}, LU EN BASE a chaque appel plutot
 * qu'injecte au constructeur : contrairement a {@code app.sync.api-key} (propriete statique), cette
 * cle n'existe qu'une fois l'auto-enrolement de {@link LicenceBootstrap} termine, et n'est donc pas
 * disponible a la construction de ce bean (cree au demarrage, avant tout enrolement).
 *
 * <p>(De)serialisation JSON explicite via l'{@link ObjectMapper} de l'application (corps en {@code
 * String}), meme discipline que {@code RestSyncClient} — pas les convertisseurs implicites de
 * {@code RestClient}, dont le format exact ne doit pas dependre de la bibliotheque Jackson choisie
 * par defaut.
 */
@Component
@ConditionalOnProperty(name = "app.licence.master-url")
public class LicenceClient {

    private static final String JETONS_PATH = "/api/licence/jetons";
    private static final String NOUVEAUX_SERVEURS_PATH = "/api/licence/nouveaux-serveurs";
    private static final String CODE_PARTENAIRE_PATH = "/api/licence/code-partenaire";

    private final RestClient restClient;
    private final EtatLicenceLocaleRepository etatLicenceLocaleRepository;
    private final ObjectMapper objectMapper;

    public LicenceClient(@Value("${app.licence.master-url}") String masterUrl,
                         EtatLicenceLocaleRepository etatLicenceLocaleRepository,
                         ObjectMapper objectMapper) {
        this.restClient = RestClient.builder().baseUrl(masterUrl).build();
        this.etatLicenceLocaleRepository = etatLicenceLocaleRepository;
        this.objectMapper = objectMapper;
    }

    /** Jeton d'installation CLIENT genere par le maitre, a usage unique, pour le client vise. */
    public record JetonGenere(String jeton, Instant expireA) {}

    /**
     * Demande au maitre de generer un jeton d'installation pour {@code customerId}. L'appelant
     * (cote maitre) est resolu depuis la cle API presentee ici, jamais depuis le corps de la requete
     * — voir {@code LicenceEnrolementController#jetons}.
     */
    public JetonGenere genererJeton(Long customerId) {
        String corps = write(new LicenceEnrolementController.JetonRequest(customerId));
        String reponse = restClient.post()
                .uri(JETONS_PATH)
                .header(LicenceApiKeyAuthFilter.HEADER, requireCleApi())
                .contentType(MediaType.APPLICATION_JSON)
                .body(corps)
                .retrieve()
                .body(String.class);
        Map<String, String> champs = read(reponse, new TypeReference<Map<String, String>>() {});
        return new JetonGenere(champs.get("jeton"), Instant.parse(champs.get("expireA")));
    }

    /**
     * Sonde le maitre pour les {@link LicenceServeur} {@code CLIENT} de SON PROPRE compte (resolu
     * cote maitre depuis la cle API presentee ici) pas encore notifies : nouvelles installations
     * jamais vues, ou revocations pas encore propagees — voir {@code
     * LicenceEnrolementController#nouveauxServeurs}. Utilise par {@code LicenceDecouverteScheduler}
     * (Task 6), un {@code GET} sans corps (meme discipline de (de)serialisation explicite que {@link
     * #genererJeton} pour la reponse).
     */
    public List<LicenceEnrolementController.NouveauServeurDto> listerNouveauxServeurs() {
        String reponse = restClient.get()
                .uri(NOUVEAUX_SERVEURS_PATH)
                .header(LicenceApiKeyAuthFilter.HEADER, requireCleApi())
                .retrieve()
                .body(String.class);
        return read(reponse, new TypeReference<List<LicenceEnrolementController.NouveauServeurDto>>() {});
    }

    /**
     * Pousse au maitre le code local (code d'enrolement du {@code SecondaryServer} materialise par
     * ce {@code PARTNER} pour {@code identifiantInstallationStable}) : le maitre le conserve pour le
     * renvoyer au client concerne a son prochain {@code /enregistrer} — voir {@code
     * LicenceEnrolementController#codePartenaire}. Utilise par {@code LicenceDecouverteScheduler}
     * (Task 6), reponse sans corps.
     */
    public void pousserCodePartenaire(String identifiantInstallationStable, String codeLocal) {
        String corps = write(new LicenceEnrolementController.CodePartenaireRequest(identifiantInstallationStable, codeLocal));
        restClient.post()
                .uri(CODE_PARTENAIRE_PATH)
                .header(LicenceApiKeyAuthFilter.HEADER, requireCleApi())
                .contentType(MediaType.APPLICATION_JSON)
                .body(corps)
                .retrieve()
                .toBodilessEntity();
    }

    private String requireCleApi() {
        String cle = etatLicenceLocaleRepository.findAll().stream()
                .findFirst()
                .map(EtatLicenceLocale::getCleApiLicence)
                .orElse(null);
        if (cle == null || cle.isBlank()) {
            throw new IllegalStateException("Aucune cle API licence disponible : cette installation "
                    + "n'est pas (encore) enrolee auprès du maitre");
        }
        return cle;
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Serialisation JSON impossible", e);
        }
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json == null ? "null" : json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Reponse JSON du maitre illisible", e);
        }
    }
}
