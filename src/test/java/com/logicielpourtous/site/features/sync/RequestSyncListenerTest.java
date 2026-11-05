package com.logicielpourtous.site.features.sync;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.hibernate.Hibernate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.ServerRole;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.request.Comment;
import com.logicielpourtous.site.features.request.CommentAddedEvent;
import com.logicielpourtous.site.features.request.CommentRepository;
import com.logicielpourtous.site.features.request.Request;
import com.logicielpourtous.site.features.request.RequestChangedEvent;
import com.logicielpourtous.site.features.request.RequestRepository;
import com.logicielpourtous.site.features.request.RequestStatus;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Preuve que le mecanisme {@code AbstractAggregateRoot} + {@code registerEvent(...)} AVANT
 * {@code save()} publie bien les evenements de domaine de {@code Request}/{@code Comment}, et que
 * {@link RequestSyncListener} alimente l'outbox depuis les VRAIS chemins des controleurs.
 *
 * <p>{@code @SpringBootTest} (et non {@code @DataJpaTest}) : il faut le listener ({@code @Component})
 * et MockMvc. Les tests {@code @Transactional} sont annules en fin de test (meme mecanisme que
 * {@code TicketRoutingRuleControllerTest}) ; le seul test non transactionnel nettoie ses lignes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
@Transactional
class RequestSyncListenerTest {

    @Autowired private RequestRepository requestRepository;
    @Autowired private SyncOutboxEntryRepository outboxRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private MockMvc mockMvc;
    @Autowired private ApplicationEvents events;
    @Autowired private CommentRepository commentRepository;
    @Autowired private SecondaryServerRepository secondaryServerRepository;

    /**
     * Ce contexte est un MAITRE ({@code app.sync.master-url} absent) : chaque evenement produit une
     * entree PAR secondaire CLIENT actif rattache au client ({@code Customer}) de la demande (Task 4,
     * fix round 1). Un seul secondaire, rattache a {@link #clientCustomer}, et toutes les demandes des
     * tests ci-dessous sont rattachees a ce client : "1 evenement = 1 entree" reste vrai. Supprimes
     * explicitement en {@code @AfterEach} (les tests NOT_SUPPORTED les committent).
     */
    private Customer clientCustomer;
    private SecondaryServer clientActif;

    @BeforeEach
    void enrolUnClientActif() {
        clientCustomer = customer("Client sync test");
        clientActif = secondaryServer("Client unique", ServerRole.CLIENT,
                SecondaryServer.SecondaryServerStatut.ACTIF, clientCustomer.getId());
    }

    @AfterEach
    void supprimerClientActif() {
        secondaryServerRepository.findById(clientActif.getId()).ifPresent(secondaryServerRepository::delete);
        if (!TestTransaction.isActive()) { // test transactionnel : le rollback suffit
            customerRepository.findById(clientCustomer.getId()).ifPresent(customerRepository::delete);
        }
    }

    // ── Step 1-2 : preuve empirique du mecanisme, sur un seul save() ────────────

    @Test
    void savingRequestPublishesEventWhenRegisteredBeforeSave() {
        Request r = requestRepository.save(nouvelleRequest());
        assertThat(events.stream(RequestChangedEvent.class)).isEmpty(); // save() sans registerChange()

        r.setStatus(RequestStatus.IN_PROGRESS);
        r.registerChange();
        assertThat(events.stream(RequestChangedEvent.class)).isEmpty(); // rien avant le save()

        requestRepository.save(r); // <-- l'evenement doit se publier ICI

        assertThat(events.stream(RequestChangedEvent.class)).hasSize(1);
        assertThat(events.stream(RequestChangedEvent.class).findFirst().orElseThrow().request().getSyncId())
                .isNotNull()
                .isEqualTo(r.getSyncId());
        assertThat(outboxFor(r.getSyncId())).hasSize(1);

        // Spring Data purge les evenements apres publication : un 2e save() sans nouvel
        // enregistrement ne republie PAS (base de la prevention d'echo, Task 4).
        requestRepository.save(r);
        assertThat(events.stream(RequestChangedEvent.class)).hasSize(1);
        assertThat(outboxFor(r.getSyncId())).hasSize(1);
    }

    /**
     * Meme preuve SANS transaction englobante, sur une instance DETACHEE (cas reel des
     * controleurs non {@code @Transactional} : {@code save()} fait un {@code merge} et renvoie une
     * AUTRE instance ; l'evenement doit quand meme partir de l'instance passee en argument).
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void publishesOnDetachedInstanceOutsideAnyTransaction() {
        Request created = requestRepository.save(nouvelleRequest()); // commit reel, instance detachee ensuite
        try {
            created.setStatus(RequestStatus.IN_PROGRESS);
            created.registerChange();
            Request merged = requestRepository.save(created);

            assertThat(merged).isNotSameAs(created); // c'est bien un merge d'instance detachee
            assertThat(events.stream(RequestChangedEvent.class)).hasSize(1);
            assertThat(outboxFor(created.getSyncId())).hasSize(1);
        } finally {
            outboxRepository.deleteAll(outboxFor(created.getSyncId()));
            requestRepository.deleteById(created.getId());
        }
    }

    /**
     * Scenario du relecteur (round de fix 2) : contexte de {@code MailService} (methodes
     * {@code @Transactional} appelees en auto-invocation, donc SANS transaction), ou le
     * {@code Comment} recoit une reference {@code Request} qui est un proxy Hibernate LAZY dont la
     * session est deja fermee. {@code persist(comment)} n'a besoin que de l'id du proxy, mais le
     * listener lisait {@code getSyncId()} -> {@code LazyInitializationException} pendant le
     * {@code save()} : commentaire annule, message IMAP retraite en boucle.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void commentOnUninitializedDetachedRequestProxyStillCreatesOutboxEntry() {
        Request created = requestRepository.save(nouvelleRequest()); // commit reel
        UUID syncId = created.getSyncId();
        Comment comment = new Comment();
        try {
            // Hors transaction : l'EntityManager partage est ferme des la sortie de l'appel ->
            // proxy detache, jamais initialise (meme situation que MailMessage::getRequest).
            Request proxy = requestRepository.getReferenceById(created.getId());
            assertThat(Hibernate.isInitialized(proxy)).isFalse();

            comment.setContent("reponse email");
            comment.setSourceEmail("client@example.com");
            comment.setRequest(proxy);
            comment.registerAdded();
            commentRepository.save(comment); // ne doit plus lever d'exception

            assertThat(comment.getId()).isNotNull();
            assertThat(events.stream(CommentAddedEvent.class)).hasSize(1);
            assertThat(outboxFor(syncId)).singleElement()
                    .satisfies(e -> assertThat(e.getPayload()).contains("CommentAdded"));
        } finally {
            outboxRepository.deleteAll(outboxFor(syncId));
            if (comment.getId() != null) commentRepository.deleteById(comment.getId());
            requestRepository.deleteById(created.getId());
        }
    }

    // ── Step 5 : chemins reels des controleurs ──────────────────────────────

    @Test
    void ticketControllerStatusChangeCreatesOutboxEntries() throws Exception {
        CustomUserDetails principal = persistedPrincipal();
        Request r = requestRepository.save(nouvelleRequest()); // pas de registerChange() : 0 evenement
        UUID syncId = r.getSyncId();
        assertThat(outboxFor(syncId)).isEmpty();

        mockMvc.perform(post("/intra/tickets/" + r.getId() + "/status")
                        .param("status", RequestStatus.IN_PROGRESS.name())
                        .with(user(principal)))
                .andExpect(status().is3xxRedirection());

        // changeStatus() sauve la Request (RequestChangedEvent) PUIS cree un commentaire
        // de journal (CommentAddedEvent) : 2 entrees, toutes deux rattachees au syncId du ticket.
        assertThat(events.stream(RequestChangedEvent.class)).hasSize(1);
        assertThat(events.stream(CommentAddedEvent.class)).hasSize(1);
        List<SyncOutboxEntry> entries = outboxFor(syncId);
        assertThat(entries).hasSize(2);
        assertThat(entries).allSatisfy(e -> {
            assertThat(e.getEntityType()).isEqualTo("Request");
            assertThat(e.getStatut()).isEqualTo(SyncOutboxEntry.SyncOutboxEntryStatut.EN_ATTENTE);
        });
        assertThat(entries).extracting(SyncOutboxEntry::getPayload)
                .anySatisfy(p -> assertThat(p).contains("RequestChanged"))
                .anySatisfy(p -> assertThat(p).contains("CommentAdded"));
    }

    @Test
    void ticketControllerCommentCreatesOutboxEntry() throws Exception {
        CustomUserDetails principal = persistedPrincipal();
        Request r = requestRepository.save(nouvelleRequest());

        mockMvc.perform(post("/intra/tickets/" + r.getId() + "/comment")
                        .param("content", "note interne")
                        .param("isPrivate", "true") // prive : pas d'envoi d'email dans le controleur
                        .with(user(principal)))
                .andExpect(status().is3xxRedirection());

        assertThat(events.stream(CommentAddedEvent.class)).hasSize(1);
        assertThat(events.stream(RequestChangedEvent.class)).isEmpty();
        assertThat(outboxFor(r.getSyncId())).singleElement()
                .satisfies(e -> assertThat(e.getPayload()).contains("CommentAdded"));
    }

    /**
     * Chemin d'edition de {@code RequestController.save} : la {@code Request} est liee depuis le
     * formulaire ({@code @ModelAttribute}) — instance neuve avec un id mais SANS {@code syncId}
     * (pas de setter, jamais lie). L'evenement porte cette instance : le listener doit retrouver
     * le {@code syncId} persiste par l'id.
     */
    @Test
    void requestControllerEditFormCreatesOutboxEntryWithPersistedSyncId() throws Exception {
        CustomUserDetails principal = persistedPrincipal();
        Customer customer = clientCustomer;
        Request r = new Request();
        r.setCustomer(customer);
        r.setTitle("Avant");
        r = requestRepository.save(r);
        UUID syncId = r.getSyncId();

        mockMvc.perform(post("/intra/customers/" + customer.getId() + "/requests/save")
                        .param("id", r.getId().toString())
                        .param("version", r.getVersion().toString()) // round-trip du champ cache (Task 3b)
                        .param("title", "Apres")
                        .param("status", RequestStatus.IN_PROGRESS.name())
                        .with(user(principal)))
                .andExpect(status().is3xxRedirection());

        assertThat(events.stream(RequestChangedEvent.class)).singleElement()
                .satisfies(e -> assertThat(e.request().getSyncId()).isNull()); // cas reellement exerce
        assertThat(outboxFor(syncId)).singleElement()
                .satisfies(e -> assertThat(e.getPayload()).contains("RequestChanged"));
        assertThat(requestRepository.findById(r.getId()).orElseThrow().getTitle()).isEqualTo("Apres");
        // Observation documentee : le merge a ecrase le syncId EN MEMOIRE de l'instance geree
        // (d'ou la requete scalaire findSyncIdById dans le listener, plutot qu'un findById)...
        assertThat(r.getSyncId()).isNull();
        // ...mais pas en base (colonne updatable = false).
        assertThat(requestRepository.findSyncIdById(r.getId())).contains(syncId);
    }

    @Test
    void requestControllerCreateFormCreatesOutboxEntry() throws Exception {
        CustomUserDetails principal = persistedPrincipal();
        Customer customer = clientCustomer;
        long avant = outboxRepository.count();

        mockMvc.perform(post("/intra/customers/" + customer.getId() + "/requests/save")
                        .param("title", "Nouvelle demande")
                        .with(user(principal)))
                .andExpect(status().is3xxRedirection());

        RequestChangedEvent event = events.stream(RequestChangedEvent.class).findFirst().orElseThrow();
        assertThat(event.request().getSyncId()).isNotNull(); // persist : @PrePersist a deja tourne
        assertThat(outboxFor(event.request().getSyncId())).hasSize(1);
        assertThat(outboxRepository.count()).isEqualTo(avant + 1);
    }

    // ── Task 4 : routage des entrees cote maitre ────────────────────────────

    @Test
    void masterFansOutOneEntryPerActiveClientSecondaryOfTheRequestCustomerOnly() {
        SecondaryServer autreClientMemeCustomer = secondaryServer("Client 2", ServerRole.CLIENT,
                SecondaryServer.SecondaryServerStatut.ACTIF, clientCustomer.getId());
        secondaryServer("Client revoque", ServerRole.CLIENT, SecondaryServer.SecondaryServerStatut.REVOQUE,
                clientCustomer.getId());
        secondaryServer("Partenaire", ServerRole.PARTNER, SecondaryServer.SecondaryServerStatut.ACTIF,
                clientCustomer.getId());

        Request r = requestRepository.save(nouvelleRequest());
        r.setStatus(RequestStatus.IN_PROGRESS);
        r.registerChange();
        requestRepository.save(r);

        assertThat(outboxFor(r.getSyncId()))
                .extracting(SyncOutboxEntry::getDestinationServerId)
                .containsExactlyInAnyOrder(clientActif.getId(), autreClientMemeCustomer.getId());
    }

    /**
     * Finding securite #1 (divulgation entre clients) : 2 serveurs CLIENT actifs de 2 clients
     * differents ; une demande du client A ne doit partir QUE vers le serveur du client A.
     */
    @Test
    void requestIsRoutedOnlyToTheSecondaryOfItsOwnCustomer() {
        Customer autreCustomer = customer("Autre client");
        SecondaryServer serveurAutreClient = secondaryServer("Serveur du client B", ServerRole.CLIENT,
                SecondaryServer.SecondaryServerStatut.ACTIF, autreCustomer.getId());

        Request r = requestRepository.save(nouvelleRequest());
        r.setTitle("Demande du client A");
        r.registerChange();
        requestRepository.save(r);

        assertThat(outboxFor(r.getSyncId()))
                .extracting(SyncOutboxEntry::getDestinationServerId)
                .containsExactly(clientActif.getId())
                .doesNotContain(serveurAutreClient.getId());
    }

    @Test
    void requestWithoutCustomerIsRoutedNowhere() {
        Request r = requestRepository.save(new Request());
        r.registerChange();
        requestRepository.save(r);

        assertThat(outboxFor(r.getSyncId())).isEmpty();
    }

    @Test
    void masterWithoutActiveClientCreatesNoEntry() {
        secondaryServerRepository.delete(clientActif);

        Request r = requestRepository.save(nouvelleRequest());
        r.registerChange();
        requestRepository.save(r);

        assertThat(outboxFor(r.getSyncId())).isEmpty();
    }

    // ── Fix round 2, I-2 : rendu d'un commentaire synchronise (sans auteur) et de l'assignation texte ──

    @Test
    void ticketDetailRendersSyncedCommentWithoutAuthorAndSyncAssignment() throws Exception {
        CustomUserDetails principal = persistedPrincipal();
        Request r = nouvelleRequest();
        r.setTitle("Ticket synchronise");
        r.setSyncAssignedTeamName("Equipe N2 du partenaire");
        r.setSyncAssignedToName("Jean Technicien");
        r = requestRepository.save(r);
        Comment recu = new Comment();
        recu.adopterSyncIdRecu(UUID.randomUUID());
        recu.setContent("Commentaire recu du partenaire");
        recu.setPublic(true);
        recu.setRequest(r);
        r.getComments().add(recu); // meme contexte de persistance (test transactionnel) que le GET
        commentRepository.save(recu); // auteur null, comme a la reception d'un commentaire synchronise

        String html = mockMvc.perform(get("/intra/tickets/" + r.getId()).with(user(principal)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Commentaire recu du partenaire")
                .contains("Synchronisation")
                .contains("Equipe N2 du partenaire")
                .contains("Jean Technicien");
    }

    // ── utilitaires ─────────────────────────────────────────────────────────

    private SecondaryServer secondaryServer(String nom, ServerRole role,
                                            SecondaryServer.SecondaryServerStatut statut, Long customerId) {
        SecondaryServer s = new SecondaryServer();
        s.setNom(nom);
        s.setRole(role);
        s.setStatut(statut);
        s.setCustomerId(customerId);
        return secondaryServerRepository.save(s);
    }

    private Customer customer(String nom) {
        Customer c = new Customer();
        c.setCompanyName(nom + " " + UUID.randomUUID());
        return customerRepository.save(c);
    }

    /** Demande rattachee au client du secondaire enrole (sinon elle n'est routee vers personne). */
    private Request nouvelleRequest() {
        Request r = new Request();
        r.setCustomer(clientCustomer);
        return r;
    }

    private List<SyncOutboxEntry> outboxFor(UUID syncId) {
        return outboxRepository.findAll().stream()
                .filter(e -> syncId.equals(e.getEntitySyncId()))
                .toList();
    }

    /**
     * Utilisateur persiste (les controleurs font {@code userRepository.findByEmail(principal.getName())})
     * + principal construit sur une instance distincte portant le role ADMIN non persiste
     * (voir {@code TicketRoutingRuleControllerTest} pour le pourquoi du CustomUserDetails / Tenant).
     */
    private CustomUserDetails persistedPrincipal() {
        Tenant tenant = new Tenant();
        tenant.setName("Sync Test Tenant");
        tenant.setSchemaName("sync_test_" + UUID.randomUUID().toString().replace("-", ""));
        tenant = tenantRepository.save(tenant);

        String email = "sync-test-" + UUID.randomUUID() + "@logicielpourtous.com";
        User persisted = new User();
        persisted.setEmail(email);
        persisted.setPassword("test");
        persisted.setFullName("Sync Test");
        persisted.setActive(true);
        persisted.setTenant(tenant);
        userRepository.save(persisted);

        User principalUser = new User();
        principalUser.setEmail(email);
        principalUser.setPassword("test");
        principalUser.setFullName("Sync Test");
        principalUser.setActive(true);
        principalUser.setTenant(tenant);
        principalUser.setRoles(Set.of(new Role("ADMIN", "Admin", "ESN")));
        return new CustomUserDetails(principalUser);
    }
}
