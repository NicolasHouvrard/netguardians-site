package com.logicielpourtous.site.features.sync;

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
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logicielpourtous.site.features.commun.ServerRole;

/**
 * {@code POST /api/sync/enroll} : echange d'un code d'enrolement a usage unique contre la cle API
 * (delivree en clair UNE SEULE FOIS, seule son empreinte est conservee).
 *
 * <p>Tests {@code @Transactional} : annules en fin de test. MockMvc s'execute dans le meme thread,
 * donc le filtre et le controleur participent a la transaction du test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EnrollmentControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private SecondaryServerRepository secondaryServerRepository;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void enrollWithValidCodeReturnsApiKeyOnce() throws Exception {
        String code = "ENR-" + UUID.randomUUID();
        SecondaryServer server = secondaryServerRepository.save(serverWithCode(code, Instant.now().plus(1, ChronoUnit.HOURS)));

        MvcResult result = mockMvc.perform(enroll(code))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        String apiKey = body.path("apiKey").asText();
        assertThat(apiKey).isNotBlank();

        SecondaryServer reloaded = secondaryServerRepository.findById(server.getId()).orElseThrow();
        assertThat(reloaded.getEnrollmentCode()).isNull();          // usage unique
        assertThat(reloaded.getEnrollmentCodeExpiresAt()).isNull();
        assertThat(reloaded.getApiKeyHash())
                .isNotNull()
                .isNotEqualTo(apiKey)                                // c'est bien une empreinte
                .isEqualTo(SyncApiKeys.hash(apiKey));
    }

    @Test
    void enrollWithAlreadyUsedCodeIsRejected() throws Exception {
        String code = "ENR-" + UUID.randomUUID();
        secondaryServerRepository.save(serverWithCode(code, Instant.now().plus(1, ChronoUnit.HOURS)));

        mockMvc.perform(enroll(code)).andExpect(status().isOk());
        mockMvc.perform(enroll(code)).andExpect(status().isUnauthorized());
    }

    @Test
    void enrollWithUnknownCodeIsRejected() throws Exception {
        mockMvc.perform(enroll("ENR-inconnu-" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void enrollWithExpiredCodeIsRejectedAndIssuesNoKey() throws Exception {
        String code = "ENR-" + UUID.randomUUID();
        SecondaryServer server = secondaryServerRepository.save(serverWithCode(code, Instant.now().minus(1, ChronoUnit.MINUTES)));

        mockMvc.perform(enroll(code)).andExpect(status().isGone());

        assertThat(secondaryServerRepository.findById(server.getId()).orElseThrow().getApiKeyHash()).isNull();
    }

    @Test
    void enrollOfRevokedServerIsRejectedAndIssuesNoKey() throws Exception {
        String code = "ENR-" + UUID.randomUUID();
        SecondaryServer s = serverWithCode(code, Instant.now().plus(1, ChronoUnit.HOURS));
        s.setStatut(SecondaryServer.SecondaryServerStatut.REVOQUE);
        SecondaryServer server = secondaryServerRepository.save(s);

        mockMvc.perform(enroll(code)).andExpect(status().isUnauthorized());

        assertThat(secondaryServerRepository.findById(server.getId()).orElseThrow().getApiKeyHash()).isNull();
    }

    @Test
    void enrollOfServerWithNonSecondaryRoleIsRejected() throws Exception {
        String code = "ENR-" + UUID.randomUUID();
        SecondaryServer s = serverWithCode(code, Instant.now().plus(1, ChronoUnit.HOURS));
        s.setRole(ServerRole.MASTER);
        secondaryServerRepository.save(s);

        mockMvc.perform(enroll(code)).andExpect(status().isConflict());
    }

    @Test
    void enrollWithoutCodeIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/sync/enroll").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    private static SecondaryServer serverWithCode(String code, Instant expiresAt) {
        SecondaryServer s = new SecondaryServer();
        s.setNom("Client test enrolement");
        s.setRole(ServerRole.CLIENT);
        s.setEnrollmentCode(code);
        s.setEnrollmentCodeExpiresAt(expiresAt);
        return s;
    }

    private static org.springframework.test.web.servlet.RequestBuilder enroll(String code) {
        return post("/api/sync/enroll")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enrollmentCode\":\"" + code + "\"}");
    }
}
