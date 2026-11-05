package com.logicielpourtous.site.features.licence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logicielpourtous.site.features.commun.ServerRole;

/**
 * {@code POST /api/licence/jetons} (genere par un {@code PARTNER} pour un de ses clients) et le
 * chemin {@code jeton} de {@code POST /api/licence/enregistrer} (consomme par l'installation
 * cliente pour s'enroler, sans jamais connaitre son propre {@code customerId}).
 *
 * <p>Tests {@code @Transactional} : annules en fin de test, meme patron que {@code
 * LicenceEnrolementControllerTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class JetonClientTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private CompteLicenceRepository compteLicenceRepository;
    @Autowired private LicenceServeurRepository licenceServeurRepository;
    @Autowired private JetonClientRepository jetonClientRepository;

    private final ObjectMapper json = new ObjectMapper();

    // ── POST /api/licence/jetons ───────────────────────────────────────────────

    @Test
    void jetons_partnerGeneratesTokenForOwnCustomer_returnsToken() throws Exception {
        CompteLicence compte = compteAvecQuota(5);
        String cleApi = LicenceCles.generate();
        enrolerPartner(compte.getId(), cleApi, LicenceServeur.LicenceServeurStatut.ACTIF);

        MvcResult result = mockMvc.perform(jetons(cleApi, 42L))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        String jetonCode = body.path("jeton").asText();
        assertThat(jetonCode).isNotBlank();
        Instant expireA = Instant.parse(body.path("expireA").asText());
        assertThat(expireA).isAfter(Instant.now());

        JetonClient jetonClient = jetonClientRepository.findByCode(jetonCode).orElseThrow();
        assertThat(jetonClient.getCompteId()).isEqualTo(compte.getId());
        assertThat(jetonClient.getCustomerId()).isEqualTo(42L);
        assertThat(jetonClient.isUtilise()).isFalse();
    }

    @Test
    void jetons_callerNotPartner_isRejected() throws Exception {
        CompteLicence compte = compteAvecQuota(5);
        String cleApi = LicenceCles.generate();
        // Role CLIENT, pas PARTNER : la generation de jeton doit rester reservee aux partenaires.
        enrolerServeur(compte.getId(), ServerRole.CLIENT, cleApi, LicenceServeur.LicenceServeurStatut.ACTIF);

        mockMvc.perform(jetons(cleApi, 42L))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(200));

        assertThat(jetonClientRepository.findAll()).isEmpty();
    }

    @Test
    void jetons_withoutApiKey_isRejected() throws Exception {
        mockMvc.perform(post("/api/licence/jetons")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":42}"))
                .andExpect(status().isUnauthorized());
    }

    // ── POST /api/licence/enregistrer (chemin jeton) ───────────────────────────

    @Test
    void enregistrer_withValidJeton_createsClientLicenceServeurWithCustomerIdFromToken() throws Exception {
        CompteLicence compte = compteAvecQuota(5);
        JetonClient jeton = jetonPourCompte(compte.getId(), 777L, Instant.now().plus(1, ChronoUnit.HOURS));
        String identifiant = UUID.randomUUID().toString();

        mockMvc.perform(enregistrerAvecJeton(jeton.getCode(), identifiant))
                .andExpect(status().isOk());

        LicenceServeur serveur = licenceServeurRepository
                .findByCompteIdAndIdentifiantInstallationStable(compte.getId(), identifiant).orElseThrow();
        assertThat(serveur.getRole()).isEqualTo(ServerRole.CLIENT);
        assertThat(serveur.getCustomerId()).isEqualTo(777L);
        assertThat(serveur.getCompteId()).isEqualTo(compte.getId());
        assertThat(serveur.getStatut()).isEqualTo(LicenceServeur.LicenceServeurStatut.ACTIF);

        assertThat(jetonClientRepository.findById(jeton.getId()).orElseThrow().isUtilise()).isTrue();
    }

    // Fix round 1, Important finding : une reprise (reponse perdue) de la MEME installation doit
    // rester idempotente — PAS consulter l'etat utilise/expire du jeton — exactement comme le
    // chemin login/mot de passe. Sans ca, un client dont le 1er /enregistrer a reussi cote serveur
    // mais dont la reponse s'est perdue n'aurait plus aucun moyen de recuperer sans un nouveau jeton.
    @Test
    void enregistrer_withSameJetonAndSameIdentifiant_isIdempotentRetryNotRejected() throws Exception {
        CompteLicence compte = compteAvecQuota(5);
        JetonClient jeton = jetonPourCompte(compte.getId(), 777L, Instant.now().plus(1, ChronoUnit.HOURS));
        String identifiant = UUID.randomUUID().toString();

        mockMvc.perform(enregistrerAvecJeton(jeton.getCode(), identifiant))
                .andExpect(status().isOk());
        // Reprise : MEME jeton (deja marque utilise par l'appel precedent), MEME identifiant —
        // simule une reponse perdue cote client, qui retente avec les memes donnees.
        mockMvc.perform(enregistrerAvecJeton(jeton.getCode(), identifiant))
                .andExpect(status().isOk());

        assertThat(licenceServeurRepository.findByCompteId(compte.getId())).hasSize(1);
        LicenceServeur serveur = licenceServeurRepository
                .findByCompteIdAndIdentifiantInstallationStable(compte.getId(), identifiant).orElseThrow();
        assertThat(serveur.getRole()).isEqualTo(ServerRole.CLIENT);
        assertThat(serveur.getCustomerId()).isEqualTo(777L);
    }

    // Fix round 2, Critical finding : identifiantInstallationStable est un champ du corps de la
    // requete, donc controle par l'appelant — le fix round 1 (idempotence) ne doit JAMAIS permettre
    // de reutiliser/muter un LicenceServeur d'un AUTRE role (ex. le PARTNER du compte) ou AUTRE
    // customer sous le MEME compte, sous peine de prise de controle cross-tenant (nouveau
    // codeEnrolement puis rotation de cle via /cle sur une installation deja active et sans rapport).
    @Test
    void enregistrer_identifiantMatchingUnrelatedPartnerUnderSameCompte_isRejectedNotReused() throws Exception {
        CompteLicence compte = compteAvecQuota(5);
        String identifiantPartage = UUID.randomUUID().toString();

        LicenceServeur partner = new LicenceServeur();
        partner.setCompteId(compte.getId());
        partner.setRole(ServerRole.PARTNER);
        partner.setIdentifiantInstallationStable(identifiantPartage);
        partner.setStatut(LicenceServeur.LicenceServeurStatut.ACTIF);
        partner.setCodeEnrolement("code-original-partner");
        partner.setCodeEnrolementExpireA(Instant.now().plus(1, ChronoUnit.HOURS));
        partner.setCodeEnrolementUtilise(true);
        partner.setCleApiHash(LicenceCles.hash(LicenceCles.generate()));
        licenceServeurRepository.save(partner);

        JetonClient jeton = jetonPourCompte(compte.getId(), 777L, Instant.now().plus(1, ChronoUnit.HOURS));

        // Un jeton valide pour le customer 777, mais presentant l'identifiantInstallationStable du
        // PARTNER du compte (fuite/devine) : doit etre refuse, PAS traite comme une reutilisation.
        mockMvc.perform(enregistrerAvecJeton(jeton.getCode(), identifiantPartage))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(409));

        LicenceServeur partnerApres = licenceServeurRepository.findById(partner.getId()).orElseThrow();
        assertThat(partnerApres.getCodeEnrolement()).isEqualTo("code-original-partner");
        assertThat(partnerApres.getCleApiHash()).isEqualTo(partner.getCleApiHash());
        assertThat(partnerApres.getRole()).isEqualTo(ServerRole.PARTNER);

        // Aucun nouveau LicenceServeur CLIENT cree pour ce jeton ; le jeton reste consommable (pas
        // marque utilise par cette tentative refusee).
        assertThat(licenceServeurRepository.findByCompteId(compte.getId())).hasSize(1);
        assertThat(jetonClientRepository.findById(jeton.getId()).orElseThrow().isUtilise()).isFalse();
    }

    @Test
    void enregistrer_identifiantMatchingDifferentCustomerClientUnderSameCompte_isRejectedNotReused() throws Exception {
        CompteLicence compte = compteAvecQuota(5);
        String identifiantPartage = UUID.randomUUID().toString();

        LicenceServeur autreClient = new LicenceServeur();
        autreClient.setCompteId(compte.getId());
        autreClient.setRole(ServerRole.CLIENT);
        autreClient.setCustomerId(111L); // customer DIFFERENT du jeton (777L) ci-dessous
        autreClient.setIdentifiantInstallationStable(identifiantPartage);
        autreClient.setStatut(LicenceServeur.LicenceServeurStatut.ACTIF);
        autreClient.setCodeEnrolement("code-original-autre-client");
        licenceServeurRepository.save(autreClient);

        JetonClient jeton = jetonPourCompte(compte.getId(), 777L, Instant.now().plus(1, ChronoUnit.HOURS));

        mockMvc.perform(enregistrerAvecJeton(jeton.getCode(), identifiantPartage))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(409));

        LicenceServeur autreClientApres = licenceServeurRepository.findById(autreClient.getId()).orElseThrow();
        assertThat(autreClientApres.getCodeEnrolement()).isEqualTo("code-original-autre-client");
        assertThat(autreClientApres.getCustomerId()).isEqualTo(111L);
        assertThat(licenceServeurRepository.findByCompteId(compte.getId())).hasSize(1);
        assertThat(jetonClientRepository.findById(jeton.getId()).orElseThrow().isUtilise()).isFalse();
    }

    @Test
    void enregistrer_withAlreadyUsedJeton_isRejected() throws Exception {
        CompteLicence compte = compteAvecQuota(5);
        JetonClient jeton = jetonPourCompte(compte.getId(), 777L, Instant.now().plus(1, ChronoUnit.HOURS));

        mockMvc.perform(enregistrerAvecJeton(jeton.getCode(), UUID.randomUUID().toString()))
                .andExpect(status().isOk());
        mockMvc.perform(enregistrerAvecJeton(jeton.getCode(), UUID.randomUUID().toString()))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(200));

        assertThat(licenceServeurRepository.findByCompteId(compte.getId())).hasSize(1);
    }

    @Test
    void enregistrer_withExpiredJeton_isRejected() throws Exception {
        CompteLicence compte = compteAvecQuota(5);
        JetonClient jeton = jetonPourCompte(compte.getId(), 777L, Instant.now().minus(1, ChronoUnit.MINUTES));

        mockMvc.perform(enregistrerAvecJeton(jeton.getCode(), UUID.randomUUID().toString()))
                .andExpect(status().isGone());

        assertThat(licenceServeurRepository.findByCompteId(compte.getId())).isEmpty();
    }

    @Test
    void enregistrer_unknownJeton_isRejected() throws Exception {
        mockMvc.perform(enregistrerAvecJeton("jeton-inconnu-" + UUID.randomUUID(), UUID.randomUUID().toString()))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(200));
    }

    @Test
    void enregistrer_jetonFromInactiveCompte_isRejected() throws Exception {
        CompteLicence compte = compteAvecQuota(5);
        compte.setActif(false);
        compteLicenceRepository.save(compte);
        JetonClient jeton = jetonPourCompte(compte.getId(), 777L, Instant.now().plus(1, ChronoUnit.HOURS));

        mockMvc.perform(enregistrerAvecJeton(jeton.getCode(), UUID.randomUUID().toString()))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(200));

        assertThat(licenceServeurRepository.findByCompteId(compte.getId())).isEmpty();
    }

    @Test
    void enregistrer_jetonFromDifferentCompteCannotBypassQuota() throws Exception {
        // compteA : quota deja atteint (1/1).
        CompteLicence compteA = compteAvecQuota(1);
        LicenceServeur existant = new LicenceServeur();
        existant.setCompteId(compteA.getId());
        existant.setRole(ServerRole.PARTNER);
        existant.setIdentifiantInstallationStable(UUID.randomUUID().toString());
        existant.setStatut(LicenceServeur.LicenceServeurStatut.ACTIF);
        licenceServeurRepository.save(existant);

        // compteB : largement sous quota — ne doit JAMAIS etre consulte par ce jeton.
        compteAvecQuota(50);

        // Le jeton appartient a compteA (plein) : le rejet doit porter sur LE QUOTA DE compteA,
        // pas etre contourne par le quota confortable d'un autre compte.
        JetonClient jeton = jetonPourCompte(compteA.getId(), 999L, Instant.now().plus(1, ChronoUnit.HOURS));

        mockMvc.perform(enregistrerAvecJeton(jeton.getCode(), UUID.randomUUID().toString()))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(409));

        assertThat(licenceServeurRepository.findByCompteId(compteA.getId())).hasSize(1);
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private CompteLicence compteAvecQuota(int quota) {
        CompteLicence compte = new CompteLicence();
        compte.setLogin("compte-licence-jeton-" + UUID.randomUUID());
        compte.setMotDePasseHash("non-utilise-dans-ces-tests");
        compte.setTypeAbonnement(CompteLicence.TypeAbonnement.ILLIMITE);
        compte.setFrequence(CompteLicence.Frequence.MENSUEL);
        compte.setNombreServeursAutorises(quota);
        compte.setActif(true);
        return compteLicenceRepository.save(compte);
    }

    private LicenceServeur enrolerPartner(Long compteId, String cleApi, LicenceServeur.LicenceServeurStatut statut) {
        return enrolerServeur(compteId, ServerRole.PARTNER, cleApi, statut);
    }

    private LicenceServeur enrolerServeur(Long compteId, ServerRole role, String cleApi,
                                          LicenceServeur.LicenceServeurStatut statut) {
        LicenceServeur serveur = new LicenceServeur();
        serveur.setCompteId(compteId);
        serveur.setRole(role);
        serveur.setIdentifiantInstallationStable(UUID.randomUUID().toString());
        serveur.setStatut(statut);
        serveur.setCleApiHash(LicenceCles.hash(cleApi));
        return licenceServeurRepository.save(serveur);
    }

    private JetonClient jetonPourCompte(Long compteId, Long customerId, Instant expireA) {
        JetonClient jeton = new JetonClient();
        jeton.setCompteId(compteId);
        jeton.setCustomerId(customerId);
        jeton.setCode("jeton-" + UUID.randomUUID());
        jeton.setExpireA(expireA);
        jeton.setUtilise(false);
        return jetonClientRepository.save(jeton);
    }

    private static RequestBuilder jetons(String cleApi, Long customerId) {
        return post("/api/licence/jetons")
                .header(LicenceApiKeyAuthFilter.HEADER, cleApi)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":" + customerId + "}");
    }

    private static RequestBuilder enregistrerAvecJeton(String jetonCode, String identifiant) {
        String corps = "{"
                + "\"jeton\":\"" + jetonCode + "\","
                + "\"identifiantInstallationStable\":\"" + identifiant + "\","
                + "\"adresseDeclaree\":\"192.168.1.20\""
                + "}";
        return post("/api/licence/enregistrer").contentType(MediaType.APPLICATION_JSON).content(corps);
    }
}
