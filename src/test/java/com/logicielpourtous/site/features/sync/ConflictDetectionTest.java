package com.logicielpourtous.site.features.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logicielpourtous.site.features.commun.ServerRole;
import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.request.Comment;
import com.logicielpourtous.site.features.request.CommentRepository;
import com.logicielpourtous.site.features.request.Request;
import com.logicielpourtous.site.features.request.RequestPriority;
import com.logicielpourtous.site.features.request.RequestRepository;
import com.logicielpourtous.site.features.request.RequestStatus;

import jakarta.persistence.EntityManager;

/**
 * Detection de conflit a la RECEPTION cote maitre ({@code POST /api/sync/events}) : la version portee
 * par le payload (version maitre sur laquelle le secondaire a base sa modification) est comparee a la
 * version actuelle AVANT toute ecriture. Perimee -> {@link SyncConflict}, rien d'applique, 200 quand
 * meme (evenement livre, conflit a trancher cote maitre).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ConflictDetectionTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private RequestRepository requestRepository;
    @Autowired private SecondaryServerRepository secondaryServerRepository;
    @Autowired private SyncConflictRepository conflictRepository;
    @Autowired private SyncOutboxEntryRepository outboxRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private CommentRepository commentRepository;

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    private String apiKey;
    /** Client ({@code Customer}) que represente le secondaire appelant. */
    private Customer customer;

    @BeforeEach
    void enrolClient() {
        customer = customer("Client conflit");
        apiKey = enrol("Client conflit", customer.getId());
    }

    @Test
    void receivingEventWithStaleVersionCreatesConflictInsteadOfApplying() throws Exception {
        Request r = requestAtVersion3("Original");

        JsonNode body = push(payload(r.getSyncId(), 2L, "Modifie par le client"));

        entityManager.clear();
        Request reloaded = requestRepository.findBySyncId(r.getSyncId()).orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo("Original");
        assertThat(reloaded.getVersion()).isEqualTo(3L);

        assertThat(conflictsFor(r.getSyncId())).singleElement().satisfies(c -> {
            assertThat(c.getVersionAttendue()).isEqualTo(3L);
            assertThat(c.getVersionRecue()).isEqualTo(2L);
            assertThat(c.isResolu()).isFalse();
            assertThat(c.getPayloadRecu()).contains("Modifie par le client");
        });
        assertThat(body.path("resultats").get(0).path("statut").asText()).isEqualTo("CONFLIT");
        assertThat(body.path("resultats").get(0).path("version").asLong()).isEqualTo(3L);
    }

    @Test
    void receivingEventWithCurrentVersionAppliesNormally() throws Exception {
        Request r = requestAtVersion3("Original");

        JsonNode body = push(payload(r.getSyncId(), 3L, "Modifie par le client"));

        entityManager.clear();
        Request reloaded = requestRepository.findBySyncId(r.getSyncId()).orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo("Modifie par le client");
        assertThat(reloaded.getStatus()).isEqualTo(RequestStatus.IN_PROGRESS);
        assertThat(reloaded.getPriority()).isEqualTo(RequestPriority.HIGH);
        assertThat(reloaded.getVersion()).isEqualTo(4L);
        assertThat(conflictsFor(r.getSyncId())).isEmpty();
        // Nouvelle version maitre renvoyee : le secondaire en fait sa version de base.
        assertThat(body.path("resultats").get(0).path("statut").asText()).isEqualTo("APPLIQUE");
        assertThat(body.path("resultats").get(0).path("version").asLong()).isEqualTo(4L);
    }

    /** Prevention d'echo cote maitre : un changement recu ne re-alimente pas l'outbox (aucun evenement de domaine). */
    @Test
    void appliedEventDoesNotCreateOutboxEntries() throws Exception {
        Request r = requestAtVersion3("Original");
        long avant = outboxRepository.count();

        push(payload(r.getSyncId(), 3L, "Modifie par le client"));

        assertThat(outboxRepository.count()).isEqualTo(avant);
    }

    /**
     * Renvoi d'un evenement deja applique (reponse du 1er push perdue -> le secondaire reessaie avec
     * l'ancienne version de base) : contenu identique a l'etat actuel, rien a trancher. Pas de faux
     * conflit, aucune ecriture, la version actuelle est renvoyee.
     */
    @Test
    void staleVersionWithIdenticalContentIsNotAConflict() throws Exception {
        Request r = requestAtVersion3("Original");
        push(payload(r.getSyncId(), 3L, "Modifie par le client")); // applique -> version 4

        JsonNode body = push(payload(r.getSyncId(), 3L, "Modifie par le client")); // meme envoi, rejoue

        entityManager.clear();
        assertThat(requestRepository.findBySyncId(r.getSyncId()).orElseThrow().getVersion()).isEqualTo(4L);
        assertThat(conflictsFor(r.getSyncId())).isEmpty();
        assertThat(body.path("resultats").get(0).path("statut").asText()).isEqualTo("APPLIQUE");
        assertThat(body.path("resultats").get(0).path("version").asLong()).isEqualTo(4L);
    }

    @Test
    void unknownSyncIdCreatesTheRequestWithTheSameSyncId() throws Exception {
        UUID syncId = UUID.randomUUID();

        JsonNode body = push(payload(syncId, 0L, "Cree cote client"));

        entityManager.clear();
        Request created = requestRepository.findBySyncId(syncId).orElseThrow();
        assertThat(created.getTitle()).isEqualTo("Cree cote client");
        assertThat(created.getStatus()).isEqualTo(RequestStatus.IN_PROGRESS);
        assertThat(conflictsFor(syncId)).isEmpty();
        assertThat(body.path("resultats").get(0).path("statut").asText()).isEqualTo("CREE");
        assertThat(body.path("resultats").get(0).path("version").asLong()).isEqualTo(created.getVersion());
    }

    // ── Fix round 2, I-2 : commentaires publics recus ────────────────────────

    @Test
    void pushedPublicCommentIsAddedOnceOnMasterWithoutOutboxEntry() throws Exception {
        Request r = requestAtVersion3("Original");
        UUID commentSyncId = UUID.randomUUID();
        long outboxAvant = outboxRepository.count();

        push(avecCommentaire(payload(r.getSyncId(), 3L, "Original"), commentSyncId, "Commentaire du client"));
        push(avecCommentaire(payload(r.getSyncId(), 3L, "Original"), commentSyncId, "Commentaire du client"));

        entityManager.flush();
        entityManager.clear();
        assertThat(commentsOf(r)).singleElement().satisfies(c -> {
            assertThat(c.getSyncId()).isEqualTo(commentSyncId);
            assertThat(c.getContent()).isEqualTo("Commentaire du client");
            assertThat(c.isPublic()).isTrue();
        });
        assertThat(outboxRepository.count()).isEqualTo(outboxAvant);
    }

    /** Un commentaire est un ajout pur : il est conserve meme quand les champs de la demande sont en conflit. */
    @Test
    void commentIsAddedEvenWhenFieldsConflict() throws Exception {
        Request r = requestAtVersion3("Original");
        UUID commentSyncId = UUID.randomUUID();

        JsonNode body = push(avecCommentaire(payload(r.getSyncId(), 2L, "Titre perime"), commentSyncId, "Ajout"));

        assertThat(body.path("resultats").get(0).path("statut").asText()).isEqualTo("CONFLIT");
        entityManager.flush();
        entityManager.clear();
        assertThat(requestRepository.findBySyncId(r.getSyncId()).orElseThrow().getTitle()).isEqualTo("Original");
        assertThat(commentsOf(r)).extracting(Comment::getSyncId).containsExactly(commentSyncId);
    }

    /** L'assignation fait autorite cote maitre : un push ne la remplace jamais par du texte. */
    @Test
    void pushNeverStoresAssignmentTextOnMaster() throws Exception {
        Request r = requestAtVersion3("Original");
        RequestSyncPayload p = payload(r.getSyncId(), 3L, "Modifie");
        p = new RequestSyncPayload(p.syncId(), p.version(), p.status(), p.type(), p.priority(), p.title(),
                p.description(), "Equipe du client", "Tech du client", List.of());

        push(p);

        entityManager.clear();
        Request apres = requestRepository.findBySyncId(r.getSyncId()).orElseThrow();
        assertThat(apres.getTitle()).isEqualTo("Modifie");
        assertThat(apres.getSyncAssignedTeamName()).isNull();
        assertThat(apres.getSyncAssignedToName()).isNull();
    }

    // ── Fix round 1 : controle d'acces par client (finding securite #2, IDOR) ─────

    /**
     * Un secondaire authentifie, rattache au client X, pousse un evenement portant le syncId d'une
     * demande du client Y : evenement REFUSE (controle par evenement), demande intacte, aucun conflit.
     * (Revue finale I-1 : plus de 403 sur le lot, voir le test suivant.)
     */
    @Test
    void pushForRequestOfAnotherCustomerIsRejectedAndNotApplied() throws Exception {
        Customer autreCustomer = customer("Client Y");
        Request demandeDeY = requestAtVersion3("Original", autreCustomer);

        JsonNode body = push(payload(demandeDeY.getSyncId(), 3L, "Pirate"));

        assertThat(body.path("resultats").get(0).path("statut").asText()).isEqualTo("REFUSE");
        entityManager.clear();
        Request reloaded = requestRepository.findBySyncId(demandeDeY.getSyncId()).orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo("Original");
        assertThat(reloaded.getVersion()).isEqualTo(3L);
        assertThat(conflictsFor(demandeDeY.getSyncId())).isEmpty();
    }

    /**
     * Revue finale I-1 : un lot melant un evenement autorise et un evenement hors-client n'est plus refuse
     * en bloc (une seule demande reaffectee empoisonnait sinon tous les push suivants). 200 : l'evenement
     * autorise est applique, l'autre est REFUSE sans aucune ecriture.
     */
    @Test
    void batchWithOneForeignEventAppliesTheOtherAndRefusesOnlyTheForeignOne() throws Exception {
        Request mienne = requestAtVersion3("Mienne");
        Request autre = requestAtVersion3("Autre", customer("Client Y"));

        String response = mockMvc.perform(post("/api/sync/events").header(SyncApiKeyAuthFilter.HEADER, apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(List.of(
                                payload(mienne.getSyncId(), 3L, "Modifiee"),
                                payload(autre.getSyncId(), 3L, "Pirate")))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode resultats = json.readTree(response).path("resultats");
        assertThat(resultats.get(0).path("syncId").asText()).isEqualTo(mienne.getSyncId().toString());
        assertThat(resultats.get(0).path("statut").asText()).isEqualTo("APPLIQUE");
        assertThat(resultats.get(1).path("syncId").asText()).isEqualTo(autre.getSyncId().toString());
        assertThat(resultats.get(1).path("statut").asText()).isEqualTo("REFUSE");
        entityManager.clear();
        assertThat(requestRepository.findBySyncId(mienne.getSyncId()).orElseThrow().getTitle()).isEqualTo("Modifiee");
        Request autreApres = requestRepository.findBySyncId(autre.getSyncId()).orElseThrow();
        assertThat(autreApres.getTitle()).isEqualTo("Autre");
        assertThat(autreApres.getVersion()).isEqualTo(3L);
        assertThat(autreApres.customerId()).isNotEqualTo(customer.getId());
        assertThat(conflictsFor(autre.getSyncId())).isEmpty();
    }

    /** Creation par la synchro : client pris du serveur appelant, jamais du payload. */
    @Test
    void createdRequestIsAttachedToTheCallingServerCustomer() throws Exception {
        UUID syncId = UUID.randomUUID();

        push(payload(syncId, 0L, "Cree cote client"));

        entityManager.clear();
        assertThat(requestRepository.findBySyncId(syncId).orElseThrow().customerId()).isEqualTo(customer.getId());
    }

    /** Secondaire rattache a aucun client : aucun push accepte. */
    @Test
    void serverWithoutCustomerIsRejected() throws Exception {
        String cleSansClient = enrol("Serveur sans client", null);

        mockMvc.perform(post("/api/sync/events").header(SyncApiKeyAuthFilter.HEADER, cleSansClient)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(List.of(payload(UUID.randomUUID(), 0L, "Orpheline")))))
                .andExpect(status().isForbidden());
    }

    @Test
    void payloadWithoutVersionIsRejectedWithoutAnyWrite() throws Exception {
        Request r = requestAtVersion3("Original");
        String sansVersion = json.writeValueAsString(List.of(payload(r.getSyncId(), null, "Sans version")));

        mockMvc.perform(post("/api/sync/events").header(SyncApiKeyAuthFilter.HEADER, apiKey)
                        .contentType(MediaType.APPLICATION_JSON).content(sansVersion))
                .andExpect(status().isBadRequest());

        entityManager.clear();
        assertThat(requestRepository.findBySyncId(r.getSyncId()).orElseThrow().getTitle()).isEqualTo("Original");
        assertThat(conflictsFor(r.getSyncId())).isEmpty();
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private Request requestAtVersion3(String title) {
        return requestAtVersion3(title, customer);
    }

    private Request requestAtVersion3(String title, Customer owner) {
        Request r = new Request();
        r.setCustomer(owner);
        r.setTitle("v0");
        r = requestRepository.saveAndFlush(r);
        for (int i = 1; i <= 3; i++) {
            r.setTitle(i == 3 ? title : "v" + i);
            r = requestRepository.saveAndFlush(r);
        }
        assertThat(r.getVersion()).isEqualTo(3L);
        return r;
    }

    private static RequestSyncPayload avecCommentaire(RequestSyncPayload p, UUID commentSyncId, String contenu) {
        return new RequestSyncPayload(p.syncId(), p.version(), p.status(), p.type(), p.priority(), p.title(),
                p.description(), p.assignedTeamName(), p.assignedToName(),
                List.of(new RequestSyncPayload.PublicComment(commentSyncId, contenu,
                        java.time.LocalDateTime.of(2026, 10, 2, 9, 30))));
    }

    private List<Comment> commentsOf(Request r) {
        return commentRepository.findAll().stream()
                .filter(c -> c.getRequest() != null && r.getId().equals(c.getRequest().getId()))
                .toList();
    }

    private String enrol(String nom, Long customerId) {
        String key = SyncApiKeys.generate();
        SecondaryServer s = new SecondaryServer();
        s.setNom(nom);
        s.setRole(ServerRole.CLIENT);
        s.setApiKeyHash(SyncApiKeys.hash(key));
        s.setStatut(SecondaryServer.SecondaryServerStatut.ACTIF);
        s.setCustomerId(customerId);
        secondaryServerRepository.saveAndFlush(s);
        return key;
    }

    private Customer customer(String nom) {
        Customer c = new Customer();
        c.setCompanyName(nom + " " + UUID.randomUUID());
        return customerRepository.saveAndFlush(c);
    }

    private static RequestSyncPayload payload(UUID syncId, Long version, String title) {
        return new RequestSyncPayload(syncId, version, RequestStatus.IN_PROGRESS, null, RequestPriority.HIGH,
                title, "desc", null, null, List.of());
    }

    private JsonNode push(RequestSyncPayload payload) throws Exception {
        String response = mockMvc.perform(post("/api/sync/events").header(SyncApiKeyAuthFilter.HEADER, apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(List.of(payload))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private List<SyncConflict> conflictsFor(UUID syncId) {
        return conflictRepository.findAll().stream().filter(c -> syncId.equals(c.getEntitySyncId())).toList();
    }
}
