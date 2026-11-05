package com.logicielpourtous.site.features.licence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.logicielpourtous.site.features.commun.ServerRole;

/**
 * {@link LicenceClient} contre les VRAIS endpoints du maitre ({@code POST /api/licence/jetons}),
 * sur un vrai port HTTP (aucun mock) : prouve que le format echange (JSON, en-tete de cle API,
 * lecture de la reponse) est bien compris dans les deux sens. Meme patron que {@code
 * features.sync.RestSyncClientIntegrationTest} pour {@code RestSyncClient} — necessaire car {@link
 * LicenceClient}, comme {@code RestSyncClient}, construit son propre {@code RestClient} interne
 * (pas de {@code RestClient.Builder} injectable a intercepter par {@code MockRestServiceServer}).
 * Ce contexte joue le maitre ; le client est instancie a la main vers {@code localhost:<port>}. Pas
 * de transaction de test (requete HTTP reelle, propre thread serveur) : nettoyage explicite.
 *
 * <p>Ajoutee au-dela de la seule liste de fichiers du plan ({@code GestionServeursControllerTest}),
 * pour ne pas laisser le vrai marshalling HTTP de {@link LicenceClient} totalement non teste
 * (le test du controleur, lui, substitue un {@link LicenceClient} de test qui ne fait jamais de
 * vrai appel HTTP — voir {@code GestionServeursControllerTest.PartnerRoleTest}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LicenceClientIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CompteLicenceRepository compteLicenceRepository;
    @Autowired private LicenceServeurRepository licenceServeurRepository;
    @Autowired private JetonClientRepository jetonClientRepository;
    @Autowired private EtatLicenceLocaleRepository etatLicenceLocaleRepository;

    private CompteLicence compte;
    private LicenceServeur partenaire;
    private EtatLicenceLocale etatLocal;

    @AfterEach
    void nettoyer() {
        if (etatLocal != null) {
            etatLicenceLocaleRepository.deleteById(etatLocal.getId());
        }
        if (partenaire != null) {
            jetonClientRepository.findAll().stream()
                    .filter(j -> j.getCompteId().equals(partenaire.getCompteId()))
                    .forEach(jetonClientRepository::delete);
            licenceServeurRepository.deleteById(partenaire.getId());
        }
        if (compte != null) {
            compteLicenceRepository.deleteById(compte.getId());
        }
    }

    @Test
    void genererJeton_realHttp_returnsTokenIssuedByMaster() {
        enrolerPartenaireEtEtatLocal();

        LicenceClient client = new LicenceClient("http://localhost:" + port, etatLicenceLocaleRepository, objectMapper);
        LicenceClient.JetonGenere jeton = client.genererJeton(777L);

        assertThat(jeton.jeton()).isNotBlank();
        assertThat(jeton.expireA()).isAfter(Instant.now());

        // findByCode() porte un verrou pessimiste (necessite une transaction) : hors de propos pour
        // cette simple lecture d'assertion, dans un test volontairement non-transactionnel (vraie
        // requete HTTP) — on relit donc via findAll() + filtre plutot.
        JetonClient enregistre = jetonClientRepository.findAll().stream()
                .filter(j -> jeton.jeton().equals(j.getCode()))
                .findFirst().orElseThrow();
        assertThat(enregistre.getCompteId()).isEqualTo(compte.getId());
        assertThat(enregistre.getCustomerId()).isEqualTo(777L);
        assertThat(enregistre.isUtilise()).isFalse();
    }

    @Test
    void genererJeton_invalidApiKey_failsLoudly() {
        enrolerPartenaireEtEtatLocal();
        etatLocal.setCleApiLicence("cle-invalide-" + UUID.randomUUID());
        etatLocal = etatLicenceLocaleRepository.save(etatLocal);

        LicenceClient client = new LicenceClient("http://localhost:" + port, etatLicenceLocaleRepository, objectMapper);

        assertThatThrownBy(() -> client.genererJeton(777L)).isInstanceOf(RuntimeException.class);
    }

    private void enrolerPartenaireEtEtatLocal() {
        compte = new CompteLicence();
        compte.setLogin("compte-licence-client-http-" + UUID.randomUUID());
        compte.setMotDePasseHash("non-utilise-dans-ce-test");
        compte.setTypeAbonnement(CompteLicence.TypeAbonnement.ILLIMITE);
        compte.setFrequence(CompteLicence.Frequence.MENSUEL);
        compte.setNombreServeursAutorises(5);
        compte.setActif(true);
        compte = compteLicenceRepository.save(compte);

        String cleApiEnClair = LicenceCles.generate();
        partenaire = new LicenceServeur();
        partenaire.setCompteId(compte.getId());
        partenaire.setRole(ServerRole.PARTNER);
        partenaire.setIdentifiantInstallationStable(UUID.randomUUID().toString());
        partenaire.setStatut(LicenceServeur.LicenceServeurStatut.ACTIF);
        partenaire.setCleApiHash(LicenceCles.hash(cleApiEnClair));
        partenaire = licenceServeurRepository.save(partenaire);

        etatLocal = new EtatLicenceLocale();
        etatLocal.setIdentifiantInstallationStable(UUID.randomUUID().toString());
        etatLocal.setCleApiLicence(cleApiEnClair);
        etatLocal.setEnregistrementTermine(true);
        etatLocal = etatLicenceLocaleRepository.save(etatLocal);
    }
}
