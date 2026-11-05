package com.logicielpourtous.site.features.deploiement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logicielpourtous.site.features.commun.ServerRole;
import com.logicielpourtous.site.features.licence.LicenceApiKeyAuthFilter;
import com.logicielpourtous.site.features.licence.LicenceCles;
import com.logicielpourtous.site.features.licence.LicenceServeur;
import com.logicielpourtous.site.features.licence.LicenceServeurRepository;

/**
 * {@code GET /api/licence/ordre-deploiement} : un {@link LicenceServeur} enrole recupere le
 * dernier {@link OrdreDeploiement} pas encore consomme par lui, montee sur la chaine de securite
 * existante {@code SecurityConfig.licenceApiFilterChain} (meme authentification par cle API que
 * {@code LicenceEnrolementControllerTest}).
 *
 * <p>Tests {@code @Transactional} : annules en fin de test, meme patron que
 * {@code LicenceEnrolementControllerTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DeploiementControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private LicenceServeurRepository licenceServeurRepository;
    @Autowired private OrdreDeploiementRepository ordreDeploiementRepository;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void ordreDeploiement_noOrderAtAll_returnsNullVersion() throws Exception {
        String cleApi = LicenceCles.generate();
        enrolerServeur(cleApi);

        MvcResult result = mockMvc.perform(ordreDeploiement(cleApi)).andExpect(status().isOk()).andReturn();

        assertThat(json.readTree(result.getResponse().getContentAsString()).get("version").isNull()).isTrue();
    }

    @Test
    void ordreDeploiement_pendingOrder_returnsVersionAndMarksConsumedForThisServer() throws Exception {
        String cleApi = LicenceCles.generate();
        LicenceServeur serveur = enrolerServeur(cleApi);
        OrdreDeploiement ordre = nouvelOrdre("v1.2.3");

        MvcResult result = mockMvc.perform(ordreDeploiement(cleApi)).andExpect(status().isOk()).andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("version").asText()).isEqualTo("v1.2.3");

        LicenceServeur apres = licenceServeurRepository.findById(serveur.getId()).orElseThrow();
        assertThat(apres.getDernierOrdreDeploiementConsommeId()).isEqualTo(ordre.getId());
    }

    @Test
    void ordreDeploiement_alreadyConsumedByThisServer_returnsNullOnSecondCall() throws Exception {
        String cleApi = LicenceCles.generate();
        enrolerServeur(cleApi);
        nouvelOrdre("v1.0.0");

        mockMvc.perform(ordreDeploiement(cleApi)).andExpect(status().isOk());
        MvcResult second = mockMvc.perform(ordreDeploiement(cleApi)).andExpect(status().isOk()).andReturn();

        assertThat(json.readTree(second.getResponse().getContentAsString()).get("version").isNull()).isTrue();
    }

    @Test
    void ordreDeploiement_newOrderAfterConsumption_isServedAgain() throws Exception {
        String cleApi = LicenceCles.generate();
        enrolerServeur(cleApi);
        nouvelOrdre("v1.0.0");
        mockMvc.perform(ordreDeploiement(cleApi)).andExpect(status().isOk());

        nouvelOrdre("v2.0.0");
        MvcResult result = mockMvc.perform(ordreDeploiement(cleApi)).andExpect(status().isOk()).andReturn();

        assertThat(json.readTree(result.getResponse().getContentAsString()).path("version").asText())
                .isEqualTo("v2.0.0");
    }

    // Deux serveurs distincts : chacun doit recevoir le MEME ordre une fois (suivi par serveur, pas
    // un verrou global sur l'ordre).
    @Test
    void ordreDeploiement_differentServers_eachGetsItOnce() throws Exception {
        String cleApiA = LicenceCles.generate();
        String cleApiB = LicenceCles.generate();
        enrolerServeur(cleApiA);
        enrolerServeur(cleApiB);
        nouvelOrdre("v3.0.0");

        MvcResult resultA = mockMvc.perform(ordreDeploiement(cleApiA)).andExpect(status().isOk()).andReturn();
        MvcResult resultB = mockMvc.perform(ordreDeploiement(cleApiB)).andExpect(status().isOk()).andReturn();

        assertThat(json.readTree(resultA.getResponse().getContentAsString()).path("version").asText())
                .isEqualTo("v3.0.0");
        assertThat(json.readTree(resultB.getResponse().getContentAsString()).path("version").asText())
                .isEqualTo("v3.0.0");
    }

    @Test
    void ordreDeploiement_withoutApiKey_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/licence/ordre-deploiement")).andExpect(status().isUnauthorized());
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private LicenceServeur enrolerServeur(String cleApi) {
        LicenceServeur serveur = new LicenceServeur();
        serveur.setCompteId(1L);
        serveur.setRole(ServerRole.PARTNER);
        serveur.setIdentifiantInstallationStable(UUID.randomUUID().toString());
        serveur.setStatut(LicenceServeur.LicenceServeurStatut.ACTIF);
        serveur.setCleApiHash(LicenceCles.hash(cleApi));
        return licenceServeurRepository.save(serveur);
    }

    private OrdreDeploiement nouvelOrdre(String version) {
        OrdreDeploiement ordre = new OrdreDeploiement();
        ordre.setVersion(version);
        return ordreDeploiementRepository.save(ordre);
    }

    private static RequestBuilder ordreDeploiement(String cleApi) {
        return get("/api/licence/ordre-deploiement").header(LicenceApiKeyAuthFilter.HEADER, cleApi);
    }
}
