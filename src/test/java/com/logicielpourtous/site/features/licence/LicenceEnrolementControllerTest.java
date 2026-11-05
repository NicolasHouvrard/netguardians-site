package com.logicielpourtous.site.features.licence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logicielpourtous.site.features.commun.ServerRole;

/**
 * {@code POST /api/licence/enregistrer} et {@code POST /api/licence/cle} : enrolement
 * automatique d'un serveur secondaire (login/mot de passe d'un {@link CompteLicence}, puis echange
 * du code d'enrolement contre une cle API dediee).
 *
 * <p>Tests {@code @Transactional} : annules en fin de test. MockMvc s'execute dans le meme thread,
 * donc le filtre et le controleur participent a la transaction du test (meme patron que
 * {@code features.sync.EnrollmentControllerTest}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LicenceEnrolementControllerTest {

    private static final String MOT_DE_PASSE = "mdp-test-" + UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @Autowired private CompteLicenceRepository compteLicenceRepository;
    @Autowired private LicenceServeurRepository licenceServeurRepository;
    @Autowired private JetonClientRepository jetonClientRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper json = new ObjectMapper();

    // ── POST /api/licence/enregistrer ─────────────────────────────────────────

    @Test
    void enregistrer_newPartnerUnderQuota_createsLicenceServeurAndReturnsCode() throws Exception {
        CompteLicence compte = compteAvecQuota(5);
        String identifiant = UUID.randomUUID().toString();

        MvcResult result = mockMvc.perform(enregistrer(compte.getLogin(), MOT_DE_PASSE, "PARTNER", identifiant))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("codeEnrolement").asText()).isNotBlank();
        Instant expireA = Instant.parse(body.path("codeEnrolementExpireA").asText());
        assertThat(expireA).isAfter(Instant.now());

        LicenceServeur serveur = licenceServeurRepository
                .findByCompteIdAndIdentifiantInstallationStable(compte.getId(), identifiant).orElseThrow();
        assertThat(serveur.getRole()).isEqualTo(ServerRole.PARTNER);
        assertThat(serveur.getStatut()).isEqualTo(LicenceServeur.LicenceServeurStatut.ACTIF);
        assertThat(serveur.getCodeEnrolement()).isEqualTo(body.path("codeEnrolement").asText());
    }

    @Test
    void enregistrer_quotaReached_isRejectedClearly() throws Exception {
        CompteLicence compte = compteAvecQuota(1);
        mockMvc.perform(enregistrer(compte.getLogin(), MOT_DE_PASSE, "PARTNER", UUID.randomUUID().toString()))
                .andExpect(status().isOk());

        // Role CLIENT ici : isole le rejet "quota" du rejet specifique "2e PARTNER" (autre test).
        mockMvc.perform(enregistrer(compte.getLogin(), MOT_DE_PASSE, "CLIENT", UUID.randomUUID().toString()))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(200));

        assertThat(licenceServeurRepository.findByCompteId(compte.getId())).hasSize(1);
    }

    @Test
    void enregistrer_sameIdentifiantInstallationStableRetried_doesNotConsumeASecondSeat() throws Exception {
        CompteLicence compte = compteAvecQuota(1);
        String identifiant = UUID.randomUUID().toString();

        mockMvc.perform(enregistrer(compte.getLogin(), MOT_DE_PASSE, "PARTNER", identifiant))
                .andExpect(status().isOk());
        mockMvc.perform(enregistrer(compte.getLogin(), MOT_DE_PASSE, "PARTNER", identifiant))
                .andExpect(status().isOk());

        assertThat(licenceServeurRepository.findByCompteId(compte.getId())).hasSize(1);
    }

    @Test
    void enregistrer_secondPartnerUnderSameCompte_isRejected() throws Exception {
        CompteLicence compte = compteAvecQuota(5);

        mockMvc.perform(enregistrer(compte.getLogin(), MOT_DE_PASSE, "PARTNER", UUID.randomUUID().toString()))
                .andExpect(status().isOk());
        mockMvc.perform(enregistrer(compte.getLogin(), MOT_DE_PASSE, "PARTNER", UUID.randomUUID().toString()))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(200));

        assertThat(licenceServeurRepository.findByCompteId(compte.getId())).hasSize(1);
    }

    @Test
    void enregistrer_wrongPassword_isRejected() throws Exception {
        CompteLicence compte = compteAvecQuota(5);

        mockMvc.perform(enregistrer(compte.getLogin(), "mauvais-mot-de-passe", "PARTNER", UUID.randomUUID().toString()))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(200));

        assertThat(licenceServeurRepository.findByCompteId(compte.getId())).isEmpty();
    }

    // Fix round 1, finding 3 : l'adresse auto-declaree ne doit pas ecraser une correction manuelle
    // (ecran /intra/licence/comptes/{id}, Step 9) faite entre un 1er enregistrement et un retry
    // idempotent (meme identifiantInstallationStable) de la MEME installation.
    @Test
    void enregistrer_idempotentRetryAfterAdminEdit_keepsTheAdminCorrectedAddress() throws Exception {
        CompteLicence compte = compteAvecQuota(5);
        String identifiant = UUID.randomUUID().toString();

        mockMvc.perform(enregistrer(compte.getLogin(), MOT_DE_PASSE, "PARTNER", identifiant))
                .andExpect(status().isOk());

        LicenceServeur serveur = licenceServeurRepository
                .findByCompteIdAndIdentifiantInstallationStable(compte.getId(), identifiant).orElseThrow();
        serveur.setAdresseDeclaree("10.0.0.99"); // correction manuelle simulee (admin, Step 9)
        licenceServeurRepository.save(serveur);

        // enregistrerBody() envoie toujours "192.168.1.10" : ce retry NE DOIT PAS l'ecraser.
        mockMvc.perform(enregistrer(compte.getLogin(), MOT_DE_PASSE, "PARTNER", identifiant))
                .andExpect(status().isOk());

        assertThat(licenceServeurRepository.findById(serveur.getId()).orElseThrow().getAdresseDeclaree())
                .isEqualTo("10.0.0.99");
    }

    // ── POST /api/licence/cle ──────────────────────────────────────────────────

    @Test
    void cle_validCode_returnsApiKeyOnceAndMarksCodeUsed() throws Exception {
        CompteLicence compte = compteAvecQuota(5);
        String identifiant = UUID.randomUUID().toString();
        String code = enregistrerEtRecupererCode(compte, identifiant);

        MvcResult result = mockMvc.perform(cle(code)).andExpect(status().isOk()).andReturn();

        String cleApi = json.readTree(result.getResponse().getContentAsString()).path("cleApi").asText();
        assertThat(cleApi).isNotBlank();

        LicenceServeur serveur = licenceServeurRepository
                .findByCompteIdAndIdentifiantInstallationStable(compte.getId(), identifiant).orElseThrow();
        assertThat(serveur.isCodeEnrolementUtilise()).isTrue();
        assertThat(serveur.getCleApiHash())
                .isNotNull()
                .isNotEqualTo(cleApi)
                .isEqualTo(LicenceCles.hash(cleApi));
    }

    @Test
    void cle_alreadyUsedCode_isRejected() throws Exception {
        CompteLicence compte = compteAvecQuota(5);
        String code = enregistrerEtRecupererCode(compte, UUID.randomUUID().toString());

        mockMvc.perform(cle(code)).andExpect(status().isOk());
        mockMvc.perform(cle(code)).andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(200));
    }

    @Test
    void cle_expiredCode_isRejected() throws Exception {
        String code = "LIC-" + UUID.randomUUID();
        LicenceServeur serveur = new LicenceServeur();
        serveur.setCompteId(999_999L);
        serveur.setRole(ServerRole.PARTNER);
        serveur.setIdentifiantInstallationStable(UUID.randomUUID().toString());
        serveur.setStatut(LicenceServeur.LicenceServeurStatut.ACTIF);
        serveur.setCodeEnrolement(code);
        serveur.setCodeEnrolementExpireA(Instant.now().minus(1, ChronoUnit.MINUTES));
        licenceServeurRepository.save(serveur);

        mockMvc.perform(cle(code)).andExpect(status().isGone());
    }

    // ── GET /api/licence/nouveaux-serveurs (Task 6) ────────────────────────────

    @Test
    void nouveauxServeurs_returnsOnlyOwnCompteClientsNotYetNotified() throws Exception {
        CompteLicence compte = compteAvecQuota(5);
        String cleApiPartenaire = LicenceCles.generate();
        enrolerServeur(compte.getId(), ServerRole.PARTNER, cleApiPartenaire, LicenceServeur.LicenceServeurStatut.ACTIF);

        LicenceServeur clientPasEncoreNotifie = clientDuCompte(compte.getId(), 42L, LicenceServeur.LicenceServeurStatut.ACTIF);
        LicenceServeur clientDejaNotifie = clientDuCompte(compte.getId(), 43L, LicenceServeur.LicenceServeurStatut.ACTIF);
        clientDejaNotifie.setNotifiePartenaire(true);
        licenceServeurRepository.save(clientDejaNotifie);

        // Autre compte : ne doit JAMAIS apparaitre dans la reponse de CE partenaire.
        CompteLicence autreCompte = compteAvecQuota(5);
        clientDuCompte(autreCompte.getId(), 999L, LicenceServeur.LicenceServeurStatut.ACTIF);

        MvcResult result = mockMvc.perform(nouveauxServeurs(cleApiPartenaire))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.isArray()).isTrue();
        assertThat(body).hasSize(1);
        assertThat(body.get(0).path("identifiantInstallationStable").asText())
                .isEqualTo(clientPasEncoreNotifie.getIdentifiantInstallationStable());
        assertThat(body.get(0).path("customerId").asLong()).isEqualTo(42L);
        assertThat(body.get(0).path("statut").asText()).isEqualTo("ACTIF");
    }

    @Test
    void nouveauxServeurs_callerNotPartner_isRejected() throws Exception {
        CompteLicence compte = compteAvecQuota(5);
        String cleApiClient = LicenceCles.generate();
        enrolerServeur(compte.getId(), ServerRole.CLIENT, cleApiClient, LicenceServeur.LicenceServeurStatut.ACTIF);

        mockMvc.perform(nouveauxServeurs(cleApiClient))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(200));
    }

    @Test
    void nouveauxServeurs_withoutApiKey_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/licence/nouveaux-serveurs"))
                .andExpect(status().isUnauthorized());
    }

    // ── POST /api/licence/code-partenaire (Task 6) ──────────────────────────────

    @Test
    void codePartenaire_storesCodeAndMarksNotifie() throws Exception {
        CompteLicence compte = compteAvecQuota(5);
        String cleApiPartenaire = LicenceCles.generate();
        enrolerServeur(compte.getId(), ServerRole.PARTNER, cleApiPartenaire, LicenceServeur.LicenceServeurStatut.ACTIF);
        LicenceServeur client = clientDuCompte(compte.getId(), 77L, LicenceServeur.LicenceServeurStatut.ACTIF);

        mockMvc.perform(codePartenaire(cleApiPartenaire, client.getIdentifiantInstallationStable(), "code-local-xyz"))
                .andExpect(status().isOk());

        LicenceServeur apres = licenceServeurRepository.findById(client.getId()).orElseThrow();
        assertThat(apres.getCodeLocalPartenaire()).isEqualTo("code-local-xyz");
        assertThat(apres.isNotifiePartenaire()).isTrue();
    }

    @Test
    void codePartenaire_unknownIdentifiant_isNotFound() throws Exception {
        CompteLicence compte = compteAvecQuota(5);
        String cleApiPartenaire = LicenceCles.generate();
        enrolerServeur(compte.getId(), ServerRole.PARTNER, cleApiPartenaire, LicenceServeur.LicenceServeurStatut.ACTIF);

        mockMvc.perform(codePartenaire(cleApiPartenaire, UUID.randomUUID().toString(), "code-local-xyz"))
                .andExpect(status().isNotFound());
    }

    // Securite : un partenaire ne doit jamais pouvoir pousser un code pour un client d'un AUTRE compte,
    // meme en devinant son identifiantInstallationStable.
    @Test
    void codePartenaire_identifiantBelongsToDifferentCompte_isNotFound() throws Exception {
        CompteLicence compteA = compteAvecQuota(5);
        String cleApiPartenaireA = LicenceCles.generate();
        enrolerServeur(compteA.getId(), ServerRole.PARTNER, cleApiPartenaireA, LicenceServeur.LicenceServeurStatut.ACTIF);

        CompteLicence compteB = compteAvecQuota(5);
        LicenceServeur clientDeB = clientDuCompte(compteB.getId(), 55L, LicenceServeur.LicenceServeurStatut.ACTIF);

        mockMvc.perform(codePartenaire(cleApiPartenaireA, clientDeB.getIdentifiantInstallationStable(), "code-vole"))
                .andExpect(status().isNotFound());

        assertThat(licenceServeurRepository.findById(clientDeB.getId()).orElseThrow().getCodeLocalPartenaire()).isNull();
    }

    // Revue finale, C2 : la revocation d'un client DEJA inscrit sync (enrollmentCode local efface par
    // EnrollmentController) est acquittee par le partenaire avec un codeLocal null — pur accuse de
    // revocation, rien a enroler : accepte, notifiePartenaire=true, codeLocalPartenaire inchange.
    @Test
    void codePartenaire_revokedClient_nullCodeLocal_isAcceptedAsRevocationAck() throws Exception {
        CompteLicence compte = compteAvecQuota(5);
        String cleApiPartenaire = LicenceCles.generate();
        enrolerServeur(compte.getId(), ServerRole.PARTNER, cleApiPartenaire, LicenceServeur.LicenceServeurStatut.ACTIF);
        LicenceServeur client = clientDuCompte(compte.getId(), 78L, LicenceServeur.LicenceServeurStatut.REVOQUE);
        client.setCodeLocalPartenaire("ancien-code-local");
        client.setNotifiePartenaire(false);
        licenceServeurRepository.save(client);

        mockMvc.perform(codePartenaireSansCode(cleApiPartenaire, client.getIdentifiantInstallationStable()))
                .andExpect(status().isOk());

        LicenceServeur apres = licenceServeurRepository.findById(client.getId()).orElseThrow();
        assertThat(apres.isNotifiePartenaire()).isTrue();
        assertThat(apres.getCodeLocalPartenaire()).isEqualTo("ancien-code-local");
    }

    // Revue finale, C2 (garde-fou) : pour un client NON revoque, codeLocal reste OBLIGATOIRE (400).
    @Test
    void codePartenaire_activeClient_nullCodeLocal_isStillBadRequest() throws Exception {
        CompteLicence compte = compteAvecQuota(5);
        String cleApiPartenaire = LicenceCles.generate();
        enrolerServeur(compte.getId(), ServerRole.PARTNER, cleApiPartenaire, LicenceServeur.LicenceServeurStatut.ACTIF);
        LicenceServeur client = clientDuCompte(compte.getId(), 79L, LicenceServeur.LicenceServeurStatut.ACTIF);

        mockMvc.perform(codePartenaireSansCode(cleApiPartenaire, client.getIdentifiantInstallationStable()))
                .andExpect(status().isBadRequest());

        LicenceServeur apres = licenceServeurRepository.findById(client.getId()).orElseThrow();
        assertThat(apres.isNotifiePartenaire()).isFalse();
        assertThat(apres.getCodeLocalPartenaire()).isNull();
    }

    // ── /enregistrer (chemin CLIENT) : adressePartenaireResolue + codeLocal (Task 6) ──

    @Test
    void enregistrer_clientPath_includesPartnerAddressAndCodeLocalOnceAvailable() throws Exception {
        CompteLicence compte = compteAvecQuota(5);
        LicenceServeur partner = enrolerServeur(compte.getId(), ServerRole.PARTNER, LicenceCles.generate(),
                LicenceServeur.LicenceServeurStatut.ACTIF);
        partner.setAdresseDeclaree("172.16.0.1");
        licenceServeurRepository.save(partner);

        JetonClient jeton = new JetonClient();
        jeton.setCompteId(compte.getId());
        jeton.setCustomerId(321L);
        jeton.setCode("jeton-" + UUID.randomUUID());
        jeton.setExpireA(Instant.now().plus(1, ChronoUnit.HOURS));
        jeton.setUtilise(false);
        jeton = jetonClientRepository.save(jeton);
        String identifiant = UUID.randomUUID().toString();

        // 1er /enregistrer : le partenaire n'a pas encore ete decouvert -> champs absents/null.
        MvcResult premier = mockMvc.perform(enregistrerAvecJeton(jeton.getCode(), identifiant))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode corpsPremier = json.readTree(premier.getResponse().getContentAsString());
        assertThat(corpsPremier.path("adressePartenaireResolue").asText()).isEqualTo("172.16.0.1");
        assertThat(corpsPremier.get("codeLocal").isNull()).isTrue();

        // Le PARTNER a pousse son code local (simule : POST /api/licence/code-partenaire).
        LicenceServeur client = licenceServeurRepository
                .findByCompteIdAndIdentifiantInstallationStable(compte.getId(), identifiant).orElseThrow();
        client.setCodeLocalPartenaire("code-local-pousse");
        client.setNotifiePartenaire(true);
        licenceServeurRepository.save(client);

        // 2e /enregistrer (retry idempotent, meme identifiant) : le code est maintenant present.
        MvcResult second = mockMvc.perform(enregistrerAvecJeton(jeton.getCode(), identifiant))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode corpsSecond = json.readTree(second.getResponse().getContentAsString());
        assertThat(corpsSecond.path("adressePartenaireResolue").asText()).isEqualTo("172.16.0.1");
        assertThat(corpsSecond.path("codeLocal").asText()).isEqualTo("code-local-pousse");
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private LicenceServeur clientDuCompte(Long compteId, Long customerId, LicenceServeur.LicenceServeurStatut statut) {
        LicenceServeur client = new LicenceServeur();
        client.setCompteId(compteId);
        client.setRole(ServerRole.CLIENT);
        client.setCustomerId(customerId);
        client.setIdentifiantInstallationStable(UUID.randomUUID().toString());
        client.setStatut(statut);
        return licenceServeurRepository.save(client);
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

    private static RequestBuilder nouveauxServeurs(String cleApi) {
        return get("/api/licence/nouveaux-serveurs").header(LicenceApiKeyAuthFilter.HEADER, cleApi);
    }

    private static RequestBuilder codePartenaire(String cleApi, String identifiant, String codeLocal) {
        String corps = "{\"identifiantInstallationStable\":\"" + identifiant + "\",\"codeLocal\":\"" + codeLocal + "\"}";
        return post("/api/licence/code-partenaire")
                .header(LicenceApiKeyAuthFilter.HEADER, cleApi)
                .contentType(MediaType.APPLICATION_JSON)
                .content(corps);
    }

    private static RequestBuilder codePartenaireSansCode(String cleApi, String identifiant) {
        String corps = "{\"identifiantInstallationStable\":\"" + identifiant + "\",\"codeLocal\":null}";
        return post("/api/licence/code-partenaire")
                .header(LicenceApiKeyAuthFilter.HEADER, cleApi)
                .contentType(MediaType.APPLICATION_JSON)
                .content(corps);
    }

    private static RequestBuilder enregistrerAvecJeton(String jetonCode, String identifiant) {
        String corps = "{\"jeton\":\"" + jetonCode + "\",\"identifiantInstallationStable\":\"" + identifiant + "\"}";
        return post("/api/licence/enregistrer").contentType(MediaType.APPLICATION_JSON).content(corps);
    }

    private CompteLicence compteAvecQuota(int quota) {
        CompteLicence compte = new CompteLicence();
        compte.setLogin("compte-licence-" + UUID.randomUUID());
        compte.setMotDePasseHash(passwordEncoder.encode(MOT_DE_PASSE));
        compte.setTypeAbonnement(CompteLicence.TypeAbonnement.ILLIMITE);
        compte.setFrequence(CompteLicence.Frequence.MENSUEL);
        compte.setNombreServeursAutorises(quota);
        compte.setActif(true);
        return compteLicenceRepository.save(compte);
    }

    private String enregistrerEtRecupererCode(CompteLicence compte, String identifiant) throws Exception {
        MvcResult result = mockMvc.perform(enregistrer(compte.getLogin(), MOT_DE_PASSE, "PARTNER", identifiant))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("codeEnrolement").asText();
    }

    private static org.springframework.test.web.servlet.RequestBuilder enregistrer(String login, String motDePasse,
                                                                                    String role, String identifiant) {
        String corps = "{"
                + "\"login\":\"" + login + "\","
                + "\"motDePasse\":\"" + motDePasse + "\","
                + "\"roleVoulu\":\"" + role + "\","
                + "\"identifiantInstallationStable\":\"" + identifiant + "\","
                + "\"adresseDeclaree\":\"192.168.1.10\""
                + "}";
        return post("/api/licence/enregistrer").contentType(MediaType.APPLICATION_JSON).content(corps);
    }

    private static org.springframework.test.web.servlet.RequestBuilder cle(String code) {
        return post("/api/licence/cle")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"codeEnrolement\":\"" + code + "\"}");
    }
}
