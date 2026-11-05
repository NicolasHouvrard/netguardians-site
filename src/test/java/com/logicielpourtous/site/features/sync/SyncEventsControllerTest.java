package com.logicielpourtous.site.features.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.request.Comment;
import com.logicielpourtous.site.features.request.CommentRepository;
import com.logicielpourtous.site.features.request.Request;
import com.logicielpourtous.site.features.request.RequestRepository;

/**
 * Endpoints {@code /api/sync/events} (push/pull) et leur authentification par cle API
 * ({@code X-Sync-Api-Key}), portee par {@link SyncApiKeyAuthFilter} et non par le JWT/la session.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SyncEventsControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private SecondaryServerRepository secondaryServerRepository;
    @Autowired private SyncOutboxEntryRepository outboxRepository;
    @Autowired private RequestRepository requestRepository;
    @Autowired private CommentRepository commentRepository;
    @Autowired private CustomerRepository customerRepository;

    private final ObjectMapper json = new ObjectMapper();

    // ── Authentification ─────────────────────────────────────────────────────

    @Test
    void pushEventsWithoutApiKeyIsRejected() throws Exception {
        mockMvc.perform(post("/api/sync/events").contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pullEventsWithoutApiKeyIsRejected() throws Exception {
        mockMvc.perform(get("/api/sync/events")).andExpect(status().isUnauthorized());
    }

    @Test
    void pushEventsWithUnknownKeyIsRejected() throws Exception {
        enrolledServer("Client A", SecondaryServer.SecondaryServerStatut.ACTIF);
        mockMvc.perform(post("/api/sync/events")
                        .header("X-Sync-Api-Key", SyncApiKeys.generate())
                        .contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pushEventsWithRevokedKeyIsRejected() throws Exception {
        Enrolled revoked = enrolledServer("Client revoque", SecondaryServer.SecondaryServerStatut.REVOQUE);

        mockMvc.perform(post("/api/sync/events")
                        .header("X-Sync-Api-Key", revoked.apiKey())
                        .contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/sync/events").header("X-Sync-Api-Key", revoked.apiKey()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pushEventsWithValidKeyPassesAuthentication() throws Exception {
        Enrolled actif = enrolledServer("Client actif", SecondaryServer.SecondaryServerStatut.ACTIF);
        // Rattache a un client (fix round 1 Task 4) : sinon tout push est refuse en 403. Lot vide :
        // l'existence reelle du client n'est pas en jeu ici.
        actif.server().setCustomerId(Long.MAX_VALUE);
        secondaryServerRepository.save(actif.server());

        // Reception reelle (Task 4, detail dans ConflictDetectionTest) : un lot vide est accepte.
        mockMvc.perform(post("/api/sync/events")
                        .header("X-Sync-Api-Key", actif.apiKey())
                        .contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isOk());
    }

    @Test
    void syncKeyDoesNotGrantAccessToOtherApiRoutes() throws Exception {
        Enrolled actif = enrolledServer("Client actif", SecondaryServer.SecondaryServerStatut.ACTIF);

        mockMvc.perform(get("/api/intra/requests").header("X-Sync-Api-Key", actif.apiKey()))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));
    }

    // ── Pull ─────────────────────────────────────────────────────────────────

    @Test
    void pullEventsReturnsOnlyEntriesForThatSecondaryServer() throws Exception {
        Customer clientA = customer("Client A");
        Customer clientB = customer("Client B");
        Enrolled a = enrolledServer("Client A", clientA);
        Enrolled b = enrolledServer("Client B", clientB);

        Request pourA = savedRequest("Ticket pour A", clientA);
        Request pourB = savedRequest("Ticket pour B", clientB);
        outboxEntry(pourA, a.server());
        outboxEntry(pourB, b.server());

        JsonNode body = pull(a.apiKey(), null);

        assertThat(syncIds(body)).containsExactly(pourA.getSyncId().toString());
        assertThat(body.get(0).path("title").asText()).isEqualTo("Ticket pour A");
        // @Version (Task 3b) : la vraie version courante de la Request, jamais null.
        assertThat(body.get(0).path("version").isIntegralNumber()).isTrue();
        assertThat(body.get(0).path("version").asLong())
                .isEqualTo(requestRepository.findById(pourA.getId()).orElseThrow().getVersion());
    }

    @Test
    void pullEventsNeverIncludesPrivateComments() throws Exception {
        Customer clientA = customer("Client A");
        Enrolled a = enrolledServer("Client A", clientA);
        Request request = savedRequest("Ticket avec commentaires", clientA);
        comment(request, "Visible", true);
        comment(request, "Interne, jamais envoye", false);
        outboxEntry(request, a.server());

        JsonNode body = pull(a.apiKey(), null);

        assertThat(body).hasSize(1);
        List<String> contents = new ArrayList<>();
        body.get(0).path("publicComments").forEach(c -> contents.add(c.path("content").asText()));
        assertThat(contents).containsExactly("Visible").doesNotContain("Interne, jamais envoye");
    }

    @Test
    void pullEventsReturnsAllEntriesEvenForSameRequest() throws Exception {
        Customer clientA = customer("Client A");
        Enrolled a = enrolledServer("Client A", clientA);
        Request request = savedRequest("Ticket modifie deux fois", clientA);
        outboxEntry(request, a.server());
        outboxEntry(request, a.server());

        assertThat(pull(a.apiKey(), null)).hasSize(2);
    }

    @Test
    void pullEventsFiltersOnSinceAndExposesCursor() throws Exception {
        Customer clientA = customer("Client A");
        Enrolled a = enrolledServer("Client A", clientA);
        Request ancienne = savedRequest("Ancienne", clientA);
        Request recente = savedRequest("Recente", clientA);
        SyncOutboxEntry e1 = outboxEntry(ancienne, a.server());
        e1.setCreeA(Instant.parse("2026-01-01T00:00:00Z"));
        outboxRepository.save(e1);
        SyncOutboxEntry e2 = outboxEntry(recente, a.server());
        e2.setCreeA(Instant.parse("2026-06-01T00:00:00Z"));
        outboxRepository.save(e2);

        MvcResult result = mockMvc.perform(get("/api/sync/events")
                        .header("X-Sync-Api-Key", a.apiKey())
                        .param("since", "2026-03-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(syncIds(body)).containsExactly(recente.getSyncId().toString());
        assertThat(result.getResponse().getHeader(SyncEventsController.CURSOR_HEADER))
                .isEqualTo("2026-06-01T00:00:00Z");
    }

    // ── Revue finale I-2 : cloisonnement par client re-verifie au moment de servir ──

    /**
     * Entree routee vers le serveur du client A, puis Request reaffectee au client B avant le pull : le
     * contenu de la demande (desormais au client B) ne doit PAS etre servi au serveur du client A.
     */
    @Test
    void pullEventsSkipsEntryWhoseRequestWasReassignedToAnotherCustomer() throws Exception {
        Customer clientA = customer("Client A");
        Customer clientB = customer("Client B");
        Enrolled a = enrolledServer("Client A", clientA);
        Request reaffectee = savedRequest("Ticket reaffecte", clientA);
        Request restee = savedRequest("Ticket reste chez A", clientA);
        outboxEntry(reaffectee, a.server());
        outboxEntry(restee, a.server());

        reaffectee.setCustomer(clientB);
        requestRepository.saveAndFlush(reaffectee);

        JsonNode body = pull(a.apiKey(), null);

        assertThat(syncIds(body)).containsExactly(restee.getSyncId().toString())
                .doesNotContain(reaffectee.getSyncId().toString());
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private record Enrolled(SecondaryServer server, String apiKey) {}

    private Enrolled enrolledServer(String nom, SecondaryServer.SecondaryServerStatut statut) {
        String apiKey = SyncApiKeys.generate();
        SecondaryServer s = new SecondaryServer();
        s.setNom(nom);
        s.setRole(ServerRole.CLIENT);
        s.setApiKeyHash(SyncApiKeys.hash(apiKey));
        s.setStatut(statut);
        return new Enrolled(secondaryServerRepository.save(s), apiKey);
    }

    /** Serveur ACTIF rattache a {@code client} (comme le routage reel, qui ne sert que les serveurs d'un client). */
    private Enrolled enrolledServer(String nom, Customer client) {
        Enrolled e = enrolledServer(nom, SecondaryServer.SecondaryServerStatut.ACTIF);
        e.server().setCustomerId(client.getId());
        secondaryServerRepository.saveAndFlush(e.server());
        return e;
    }

    private Customer customer(String nom) {
        Customer c = new Customer();
        c.setCompanyName(nom + " " + UUID.randomUUID());
        return customerRepository.saveAndFlush(c);
    }

    private Request savedRequest(String title, Customer client) {
        Request r = new Request();
        r.setTitle(title);
        r.setCustomer(client);
        return requestRepository.saveAndFlush(r);
    }

    private void comment(Request request, String content, boolean isPublic) {
        Comment c = new Comment();
        c.setContent(content);
        c.setPublic(isPublic);
        c.setRequest(request);
        request.getComments().add(c);
        commentRepository.saveAndFlush(c);
    }

    private SyncOutboxEntry outboxEntry(Request request, SecondaryServer destination) {
        SyncOutboxEntry e = new SyncOutboxEntry();
        e.setEntityType("Request");
        e.setEntitySyncId(request.getSyncId());
        e.setPayload("{}");
        e.setDestinationServerId(destination.getId());
        return outboxRepository.saveAndFlush(e);
    }

    private JsonNode pull(String apiKey, String since) throws Exception {
        var builder = get("/api/sync/events").header("X-Sync-Api-Key", apiKey);
        if (since != null) builder = builder.param("since", since);
        MvcResult result = mockMvc.perform(builder).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static List<String> syncIds(JsonNode body) {
        List<String> ids = new ArrayList<>();
        body.forEach(n -> ids.add(n.path("syncId").asText()));
        return ids;
    }
}
