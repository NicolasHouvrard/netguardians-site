package com.logicielpourtous.site.features.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

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

/**
 * {@link RestSyncClient} contre les VRAIS endpoints du maitre, sur un vrai port HTTP (aucun mock) :
 * prouve que le format echange (records, dates des commentaires, en-tete curseur, cle API) est bien
 * compris dans les deux sens. Ce contexte joue le maitre ; le client est instancie a la main vers
 * {@code localhost:<port>}. Pas de transaction de test (requetes HTTP reelles) : nettoyage explicite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RestSyncClientIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SecondaryServerRepository secondaryServerRepository;
    @Autowired private SyncOutboxEntryRepository outboxRepository;
    @Autowired private SyncConflictRepository conflictRepository;
    @Autowired private RequestRepository requestRepository;
    @Autowired private CommentRepository commentRepository;

    @Autowired private CustomerRepository customerRepository;

    private Customer customer;
    private SecondaryServer server;
    private String apiKey;
    private final List<UUID> syncIds = new ArrayList<>();

    @BeforeEach
    void enrol() {
        Customer c = new Customer();
        c.setCompanyName("Client HTTP reel " + UUID.randomUUID());
        customer = customerRepository.save(c);
        apiKey = SyncApiKeys.generate();
        SecondaryServer s = new SecondaryServer();
        s.setCustomerId(customer.getId());
        s.setNom("Client HTTP reel");
        s.setRole(ServerRole.CLIENT);
        s.setApiKeyHash(SyncApiKeys.hash(apiKey));
        server = secondaryServerRepository.save(s);
    }

    @AfterEach
    void nettoyer() {
        for (UUID syncId : syncIds) {
            outboxRepository.findAll().stream().filter(e -> syncId.equals(e.getEntitySyncId()))
                    .forEach(outboxRepository::delete);
            conflictRepository.findAll().stream().filter(c -> syncId.equals(c.getEntitySyncId()))
                    .forEach(conflictRepository::delete);
            requestRepository.findBySyncId(syncId).ifPresent(r -> {
                commentRepository.findAll().stream()
                        .filter(c -> c.getRequest() != null && r.getId().equals(c.getRequest().getId()))
                        .forEach(commentRepository::delete);
                requestRepository.delete(r);
            });
        }
        secondaryServerRepository.deleteById(server.getId());
        customerRepository.deleteById(customer.getId());
    }

    @Test
    void pushCreatesThenAppliesThenReportsConflictOverRealHttp() {
        RestSyncClient client = client(apiKey);
        UUID syncId = track(UUID.randomUUID());

        List<SyncReceptionService.ResultatReception> cree = client.push(List.of(payload(syncId, 0L, "Creee")));
        assertThat(cree).singleElement().satisfies(r -> {
            assertThat(r.syncId()).isEqualTo(syncId);
            assertThat(r.statut()).isEqualTo(SyncReceptionService.StatutReception.CREE);
        });
        long version = cree.get(0).version();

        assertThat(client.push(List.of(payload(syncId, version, "Modifiee"))))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.statut()).isEqualTo(SyncReceptionService.StatutReception.APPLIQUE);
                    assertThat(r.version()).isEqualTo(version + 1);
                });
        assertThat(client.push(List.of(payload(syncId, version, "Perimee"))))
                .singleElement()
                .satisfies(r -> assertThat(r.statut()).isEqualTo(SyncReceptionService.StatutReception.CONFLIT));

        assertThat(requestRepository.findBySyncId(syncId).orElseThrow().getTitle()).isEqualTo("Modifiee");
    }

    @Test
    void pullReadsPayloadsWithCommentDatesAndCursorOverRealHttp() {
        Request r = new Request();
        r.setCustomer(customer);
        r.setTitle("Pour le client");
        r = requestRepository.save(r);
        track(r.getSyncId());
        Comment c = new Comment();
        c.setContent("Visible");
        c.setPublic(true);
        c.setRequest(r);
        commentRepository.save(c);
        Comment prive = new Comment();
        prive.setContent("Interne");
        prive.setPublic(false);
        prive.setRequest(r);
        commentRepository.save(prive);
        SyncOutboxEntry e = new SyncOutboxEntry();
        e.setEntityType("Request");
        e.setEntitySyncId(r.getSyncId());
        e.setPayload("{}");
        e.setDestinationServerId(server.getId());
        outboxRepository.save(e);

        SyncClient.PullResult result = client(apiKey).pull(null);

        assertThat(result.events()).singleElement().satisfies(p -> {
            assertThat(p.title()).isEqualTo("Pour le client");
            assertThat(p.version()).isNotNull();
            assertThat(p.publicComments()).singleElement().satisfies(pc -> {
                assertThat(pc.content()).isEqualTo("Visible");
                assertThat(pc.createdAt()).isNotNull();
            });
        });
        assertThat(result.cursor()).isNotNull();
        assertThat(client(apiKey).pull(result.cursor()).events()).isEmpty();
    }

    @Test
    void invalidKeyFailsLoudly() {
        assertThatThrownBy(() -> client(SyncApiKeys.generate()).pull(null)).isInstanceOf(RuntimeException.class);
    }

    private RestSyncClient client(String key) {
        return new RestSyncClient("http://localhost:" + port, key, objectMapper);
    }

    private UUID track(UUID syncId) {
        syncIds.add(syncId);
        return syncId;
    }

    private static RequestSyncPayload payload(UUID syncId, long version, String title) {
        return new RequestSyncPayload(syncId, version, RequestStatus.IN_PROGRESS, null, RequestPriority.HIGH,
                title, null, null, null, List.of());
    }
}
