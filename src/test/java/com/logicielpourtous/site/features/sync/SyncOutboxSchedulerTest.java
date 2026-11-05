package com.logicielpourtous.site.features.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.ResourceAccessException;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;
import com.logicielpourtous.site.features.request.Comment;
import com.logicielpourtous.site.features.request.CommentRepository;
import com.logicielpourtous.site.features.request.Request;
import com.logicielpourtous.site.features.request.RequestPriority;
import com.logicielpourtous.site.features.request.RequestRepository;
import com.logicielpourtous.site.features.request.RequestStatus;
import com.logicielpourtous.site.features.sync.SyncOutboxEntry.SyncOutboxEntryStatut;
import com.logicielpourtous.site.features.sync.SyncReceptionService.ResultatReception;
import com.logicielpourtous.site.features.sync.SyncReceptionService.StatutReception;

import jakarta.persistence.EntityManager;

/**
 * Job planifie cote SECONDAIRE ({@code app.sync.master-url} configure) : push de l'outbox locale,
 * pull des changements du maitre, prevention d'echo. Le transport est remplace par un
 * {@link FakeSyncClient} (aucun appel reseau) ; le {@code @Scheduled} reel est neutralise par un
 * delai initial d'une heure, les cycles sont declenches a la main.
 */
@SpringBootTest(properties = {
        "app.sync.master-url=http://localhost:1",
        "app.sync.api-key=cle-de-test",
        "app.sync.initial-delay-ms=3600000",
        // Plafond bas (defaut 20) pour les tests du fix round 4 ; les autres tests n'atteignent jamais
        // plus de 1 echec par entree (ou partent de 2 tentatives et reussissent).
        "app.sync.max-tentatives=3"
})
@AutoConfigureMockMvc
@Transactional
class SyncOutboxSchedulerTest {

    @Autowired private SyncOutboxScheduler scheduler;
    @Autowired private FakeSyncClient client;
    @Autowired private SyncOutboxEntryRepository outboxRepository;
    @Autowired private SyncVersionConnueRepository versionRepository;
    @Autowired private RequestRepository requestRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private CommentRepository commentRepository;
    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private UserRepository userRepository;

    @TestConfiguration
    static class FakeClientConfig {
        @Bean
        @Primary
        FakeSyncClient fakeSyncClient() {
            return new FakeSyncClient();
        }
    }

    /** Transport simule : enregistre les appels, reponses programmables. */
    static class FakeSyncClient implements SyncClient {
        final List<List<RequestSyncPayload>> pushes = new ArrayList<>();
        final List<Instant> pullSinces = new ArrayList<>();
        Function<List<RequestSyncPayload>, List<ResultatReception>> onPush = events -> List.of();
        Function<Instant, SyncClient.PullResult> onPull = since -> new SyncClient.PullResult(List.of(), null);

        @Override
        public List<ResultatReception> push(List<RequestSyncPayload> events) {
            pushes.add(events);
            return onPush.apply(events);
        }

        @Override
        public SyncClient.PullResult pull(Instant since) {
            pullSinces.add(since);
            return onPull.apply(since);
        }
    }

    @BeforeEach
    void resetFake() {
        client.pushes.clear();
        client.pullSinces.clear();
        client.onPush = events -> List.of();
        client.onPull = since -> new SyncClient.PullResult(List.of(), null);
        scheduler.reinitialiserCurseur();
    }

    // ── Step 4 : push ────────────────────────────────────────────────────────

    @Test
    void schedulerPushesPendingOutboxEntriesAndMarksThemSent() {
        Request r = savedRequest("A pousser");
        SyncOutboxEntry entry = pendingEntry(r.getSyncId());
        client.onPush = events -> events.stream()
                .map(e -> new ResultatReception(e.syncId(), StatutReception.APPLIQUE, 7L)).toList();

        scheduler.pousser();

        assertThat(client.pushes).singleElement()
                .satisfies(lot -> assertThat(lot).extracting(RequestSyncPayload::syncId).containsExactly(r.getSyncId()));
        assertThat(reload(entry).getStatut()).isEqualTo(SyncOutboxEntryStatut.ENVOYE);
        // Nouvelle version maitre renvoyee : devient la version de base de ce secondaire.
        assertThat(versionRepository.findByEntitySyncId(r.getSyncId()))
                .hasValueSatisfying(v -> assertThat(v.getVersionMaitre()).isEqualTo(7L));
    }

    @Test
    void schedulerMarksEchecOnNetworkFailureWithoutCrashing() {
        Request r = savedRequest("Reseau coupe");
        SyncOutboxEntry entry = pendingEntry(r.getSyncId());
        client.onPush = events -> { throw new ResourceAccessException("Connection refused"); };

        assertThatCode(() -> scheduler.synchroniser()).doesNotThrowAnyException();

        SyncOutboxEntry apres = reload(entry);
        assertThat(apres.getStatut()).isEqualTo(SyncOutboxEntryStatut.ECHEC);
        // Residu C-1 : un echec de TRANSPORT ne fait pas avancer le compteur (ni le backoff, ni le plafond).
        assertThat(apres.getTentatives()).isEqualTo(0);
        assertThat(apres.getDerniereTentativeA()).isNotNull();

        // Compteur fige a 0 -> backoff nul : retente au cycle suivant (un appel HTTP par cycle, comme le pull).
        scheduler.pousser();
        assertThat(client.pushes).hasSize(2);
    }

    @Test
    void failedEntryIsRetriedOnceBackoffHasElapsed() {
        Request r = savedRequest("Nouvel essai");
        SyncOutboxEntry entry = pendingEntry(r.getSyncId());
        entry.setStatut(SyncOutboxEntryStatut.ECHEC);
        entry.setTentatives(2);
        entry.setDerniereTentativeA(Instant.now().minusSeconds(11)); // > 2 * 5 s
        outboxRepository.saveAndFlush(entry);
        client.onPush = events -> events.stream()
                .map(e -> new ResultatReception(e.syncId(), StatutReception.APPLIQUE, 1L)).toList();

        scheduler.pousser();

        assertThat(client.pushes).hasSize(1);
        assertThat(reload(entry).getStatut()).isEqualTo(SyncOutboxEntryStatut.ENVOYE);
    }

    /** La version poussee est la version MAITRE connue, pas la version locale (compteurs independants). */
    @Test
    void pushCarriesKnownMasterVersionNotLocalVersion() {
        Request r = savedRequest("v0");
        r.setTitle("v1");
        r = requestRepository.saveAndFlush(r);
        r.setTitle("v2");
        r = requestRepository.saveAndFlush(r);
        assertThat(r.getVersion()).isEqualTo(2L);
        knownMasterVersion(r.getSyncId(), 9L);
        pendingEntry(r.getSyncId());
        client.onPush = events -> events.stream()
                .map(e -> new ResultatReception(e.syncId(), StatutReception.APPLIQUE, 10L)).toList();

        scheduler.pousser();

        assertThat(client.pushes.get(0)).singleElement().satisfies(p -> {
            assertThat(p.version()).isEqualTo(9L);
            assertThat(p.title()).isEqualTo("v2");
        });
    }

    /** Plusieurs entrees pour la meme Request : un seul payload (etat courant), toutes marquees envoyees. */
    @Test
    void severalEntriesForSameRequestArePushedOnce() {
        Request r = savedRequest("Deux modifications");
        SyncOutboxEntry e1 = pendingEntry(r.getSyncId());
        SyncOutboxEntry e2 = pendingEntry(r.getSyncId());
        client.onPush = events -> events.stream()
                .map(e -> new ResultatReception(e.syncId(), StatutReception.CREE, 0L)).toList();

        scheduler.pousser();

        assertThat(client.pushes.get(0)).hasSize(1);
        assertThat(reload(e1).getStatut()).isEqualTo(SyncOutboxEntryStatut.ENVOYE);
        assertThat(reload(e2).getStatut()).isEqualTo(SyncOutboxEntryStatut.ENVOYE);
    }

    /** Conflit : evenement LIVRE (le maitre tranche), donc pas renvoye en boucle ; version de base inchangee. */
    @Test
    void conflictResultMarksEntrySentAndKeepsBaseVersion() {
        Request r = savedRequest("En conflit");
        knownMasterVersion(r.getSyncId(), 3L);
        SyncOutboxEntry entry = pendingEntry(r.getSyncId());
        client.onPush = events -> events.stream()
                .map(e -> new ResultatReception(e.syncId(), StatutReception.CONFLIT, 5L)).toList();

        scheduler.pousser();

        assertThat(reload(entry).getStatut()).isEqualTo(SyncOutboxEntryStatut.ENVOYE);
        assertThat(versionRepository.findByEntitySyncId(r.getSyncId()).orElseThrow().getVersionMaitre())
                .isEqualTo(3L);
    }

    @Test
    void errorResultFromMasterMarksEntryEchecForRetry() {
        Request r = savedRequest("Erreur maitre");
        SyncOutboxEntry entry = pendingEntry(r.getSyncId());
        client.onPush = events -> events.stream()
                .map(e -> new ResultatReception(e.syncId(), StatutReception.ERREUR, null)).toList();

        scheduler.pousser();

        assertThat(reload(entry).getStatut()).isEqualTo(SyncOutboxEntryStatut.ECHEC);
        assertThat(reload(entry).getTentatives()).isEqualTo(1);
    }

    /** Point 2 du dispatch, cote secondaire : une seule entree, SANS destinataire (poussee vers le maitre). */
    @Test
    void listenerOnSecondaryCreatesSingleEntryWithoutDestination() {
        Request r = savedRequest("Modif locale");
        r.setTitle("Modifiee");
        r.registerChange();
        requestRepository.saveAndFlush(r);

        assertThat(entriesFor(r.getSyncId())).singleElement()
                .satisfies(e -> assertThat(e.getDestinationServerId()).isNull());
    }

    // ── Fix round 2, I-1 : payload FIGE a la creation de l'entree ─────────────

    /**
     * Scenario du relecteur : base v3 -> edition CLIENT (vrai listener) -> push en echec -> pull v4
     * "Edition PARTENAIRE" -> reessai. Le payload repousse doit etre l'edition CLIENT (version 3) : le maitre
     * l'enregistre en conflit au lieu de la perdre en silence.
     *
     * <p>Depuis le fix round 3, le pull est DIFFERE tant que l'edition CLIENT n'est pas livree (il n'ecrase
     * plus l'etat local), puis applique une fois le conflit livre : le secondaire finit sur l'etat maitre.
     */
    @Test
    void editWhosePushFailedIsNotLostWhenAPullOverwritesBeforeRetry() {
        Request r = savedRequest("Original");
        knownMasterVersion(r.getSyncId(), 3L);
        r.setTitle("Edition CLIENT");
        r.registerChange();
        r = requestRepository.saveAndFlush(r);
        UUID syncId = r.getSyncId();
        SyncOutboxEntry entry = entriesFor(syncId).get(0);
        assertThat(entriesFor(syncId)).hasSize(1);

        client.onPush = events -> { throw new ResourceAccessException("Connection refused"); };
        scheduler.pousser();
        assertThat(reload(entry).getStatut()).isEqualTo(SyncOutboxEntryStatut.ECHEC);

        client.onPull = since -> new SyncClient.PullResult(
                List.of(payload(syncId, 4L, "Edition PARTENAIRE")), Instant.now());
        scheduler.tirer();
        // Differe : l'edition locale en attente n'est pas ecrasee, la base n'avance pas.
        assertThat(requestRepository.findBySyncId(syncId).orElseThrow().getTitle()).isEqualTo("Edition CLIENT");
        assertThat(versionRepository.findByEntitySyncId(syncId).orElseThrow().getVersionMaitre()).isEqualTo(3L);

        SyncOutboxEntry echec = reload(entry);
        echec.setDerniereTentativeA(Instant.now().minusSeconds(60)); // fin du backoff
        outboxRepository.saveAndFlush(echec);
        client.onPush = events -> events.stream()
                .map(e -> new ResultatReception(e.syncId(), StatutReception.CONFLIT, 4L)).toList();

        scheduler.pousser();

        assertThat(client.pushes.get(client.pushes.size() - 1)).singleElement().satisfies(p -> {
            assertThat(p.title()).isEqualTo("Edition CLIENT");
            assertThat(p.version()).isEqualTo(3L);
        });
        assertThat(reload(entry).getStatut()).isEqualTo(SyncOutboxEntryStatut.ENVOYE);
        // Conflit livre -> le pull differe (v4 > base 3) est applique : etat maitre, base 4.
        Request finale = requestRepository.findBySyncId(syncId).orElseThrow();
        assertThat(finale.getTitle()).isEqualTo("Edition PARTENAIRE");
        SyncVersionConnue connue = versionRepository.findByEntitySyncId(syncId).orElseThrow();
        assertThat(connue.getVersionMaitre()).isEqualTo(4L);
        assertThat(connue.getPayloadPullDiffere()).isNull();
    }

    /**
     * Pas de faux conflit : une edition faite PENDANT un push (figee avec l'ancienne base) est envoyee
     * avec la base avancee par ce push, puisqu'aucun pull n'a ecrase l'etat local depuis.
     */
    @Test
    void editMadeDuringOwnPushIsSentWithTheAdvancedBase() {
        Request r = savedRequest("Original");
        knownMasterVersion(r.getSyncId(), 3L);
        UUID syncId = r.getSyncId();
        editViaListener(syncId, "Edition 1");

        client.onPush = events -> {
            editViaListener(syncId, "Edition 2"); // pendant le push : figee avec la base 3
            return events.stream().map(e -> new ResultatReception(e.syncId(), StatutReception.APPLIQUE, 4L)).toList();
        };
        scheduler.pousser();
        client.onPush = events -> events.stream()
                .map(e -> new ResultatReception(e.syncId(), StatutReception.APPLIQUE, 5L)).toList();
        scheduler.pousser();

        assertThat(client.pushes).hasSize(2);
        assertThat(client.pushes.get(1)).singleElement().satisfies(p -> {
            assertThat(p.title()).isEqualTo("Edition 2");
            assertThat(p.version()).isEqualTo(4L);
        });
    }

    /** Deux editions sur la meme base : un seul payload, le dernier etat (cumulatif). */
    @Test
    void successiveEditsOnSameBaseArePushedAsTheLastFrozenState() {
        Request r = savedRequest("Original");
        knownMasterVersion(r.getSyncId(), 3L);
        editViaListener(r.getSyncId(), "Edition 1");
        editViaListener(r.getSyncId(), "Edition 2");
        client.onPush = events -> events.stream()
                .map(e -> new ResultatReception(e.syncId(), StatutReception.APPLIQUE, 4L)).toList();

        scheduler.pousser();

        assertThat(client.pushes.get(0)).singleElement().satisfies(p -> {
            assertThat(p.title()).isEqualTo("Edition 2");
            assertThat(p.version()).isEqualTo(3L);
        });
        assertThat(entriesFor(r.getSyncId())).allSatisfy(
                e -> assertThat(e.getStatut()).isEqualTo(SyncOutboxEntryStatut.ENVOYE));
    }

    /** Le listener du secondaire stocke le payload complet, fige a l'instant de l'edition. */
    @Test
    void listenerOnSecondaryFreezesFullPayloadAtEditTime() {
        Request r = savedRequest("Original");
        knownMasterVersion(r.getSyncId(), 3L);
        editViaListener(r.getSyncId(), "Edition figee");
        editTitleWithoutEvent(r.getSyncId(), "Modifie apres coup");

        assertThat(entriesFor(r.getSyncId())).singleElement().satisfies(e -> {
            assertThat(e.getPayload()).contains("\"title\":\"Edition figee\"").contains("\"version\":3");
            assertThat(e.getPayload()).doesNotContain("Modifie apres coup");
        });
    }

    /**
     * Re-sondage complet (apres redemarrage) qui renvoie l'etat maitre INCHANGE pendant qu'une edition
     * locale attend : differe (l'edition n'est pas ecrasee), puis ECARTE une fois l'edition appliquee par le
     * maitre (perime : base deja avancee au-dela). Le secondaire garde son edition, comme le maitre.
     */
    @Test
    void pullWhileEditPendingIsDeferredThenDiscardedOnceMasterAppliedTheEdit() {
        Request r = savedRequest("Etat maitre");
        knownMasterVersion(r.getSyncId(), 3L);
        UUID syncId = r.getSyncId();
        editViaListener(syncId, "Edition CLIENT");
        client.onPull = since -> new SyncClient.PullResult(List.of(payload(syncId, 3L, "Etat maitre")), null);
        scheduler.tirer();
        assertThat(requestRepository.findBySyncId(syncId).orElseThrow().getTitle()).isEqualTo("Edition CLIENT");
        client.onPush = events -> events.stream()
                .map(e -> new ResultatReception(e.syncId(), StatutReception.APPLIQUE, 4L)).toList();

        scheduler.pousser();

        assertThat(client.pushes.get(0)).singleElement().satisfies(p -> {
            assertThat(p.title()).isEqualTo("Edition CLIENT");
            assertThat(p.version()).isEqualTo(3L);
        });
        entityManager.flush();
        entityManager.clear();
        assertThat(requestRepository.findBySyncId(syncId).orElseThrow().getTitle()).isEqualTo("Edition CLIENT");
        assertThat(entriesFor(syncId)).allSatisfy(e -> assertThat(e.getStatut()).isEqualTo(SyncOutboxEntryStatut.ENVOYE));
        SyncVersionConnue connue = versionRepository.findByEntitySyncId(syncId).orElseThrow();
        assertThat(connue.getVersionMaitre()).isEqualTo(4L);
        assertThat(connue.getPayloadPullDiffere()).isNull();
    }

    /** Une entree en backoff retient les entrees suivantes de la meme Request (ordre preserve). */
    @Test
    void entryInBackoffHoldsBackLaterEntriesOfSameRequest() {
        Request r = savedRequest("Original");
        UUID syncId = r.getSyncId();
        editViaListener(syncId, "Edition 1");
        SyncOutboxEntry premiere = entriesFor(syncId).get(0);
        premiere.setStatut(SyncOutboxEntryStatut.ECHEC);
        premiere.setTentatives(3);
        premiere.setDerniereTentativeA(Instant.now()); // backoff de 15 s en cours
        outboxRepository.saveAndFlush(premiere);
        editViaListener(syncId, "Edition 2");

        scheduler.pousser();

        assertThat(client.pushes).isEmpty();
    }

    // ── Fix round 3 ──────────────────────────────────────────────────────────

    /**
     * I-A (scenario du re-relecteur) : E1 (description) figee en base v3, push en echec ; re-sondage qui
     * renvoie l'etat maitre INCHANGE (v3) ; E2 (titre) figee en base v3 ; push suivant. Le payload pousse
     * doit contenir E1 ET E2 : l'edition E1 ne doit jamais etre perdue par la fusion.
     */
    @Test
    void pullBetweenTwoPendingEditsNeverLosesTheFirstEdit() {
        Request r = savedRequest("Titre maitre");
        knownMasterVersion(r.getSyncId(), 3L);
        UUID syncId = r.getSyncId();
        editDescriptionViaListener(syncId, "Description E1");

        client.onPush = events -> { throw new ResourceAccessException("Connection refused"); };
        scheduler.pousser();
        client.onPull = since -> new SyncClient.PullResult(
                List.of(new RequestSyncPayload(syncId, 3L, RequestStatus.COMPLETED, null, RequestPriority.LOW,
                        "Titre maitre", null, null, null, List.of())), null);
        scheduler.tirer(); // re-sondage : etat maitre inchange
        editViaListener(syncId, "Titre E2");

        entriesFor(syncId).forEach(e -> { // fin du backoff de E1
            e.setDerniereTentativeA(Instant.now().minusSeconds(60));
            outboxRepository.saveAndFlush(e);
        });
        client.onPush = events -> events.stream()
                .map(e -> new ResultatReception(e.syncId(), StatutReception.APPLIQUE, 4L)).toList();
        scheduler.pousser();

        List<RequestSyncPayload> envoyes = client.pushes.get(client.pushes.size() - 1);
        assertThat(envoyes).isNotEmpty();
        RequestSyncPayload dernier = envoyes.get(envoyes.size() - 1);
        assertThat(dernier.description()).isEqualTo("Description E1");
        assertThat(dernier.title()).isEqualTo("Titre E2");
        assertThat(envoyes).allSatisfy(p -> assertThat(p.version()).isEqualTo(3L));
    }

    /**
     * I-B : chemin REEL d'un changement de statut ({@code POST /intra/tickets/{id}/status}) sur un
     * secondaire. Le controleur sauve la Request (RequestChanged) PUIS un commentaire journal public
     * (CommentAdded) sans l'ajouter a la collection en memoire : le payload pousse doit quand meme
     * contenir ce commentaire.
     */
    @Test
    void statusChangeThroughRealEndpointPushesTheJournalComment() throws Exception {
        Request r = savedRequest("Ticket client");
        knownMasterVersion(r.getSyncId(), 2L);
        CustomUserDetails principal = persistedPrincipal();

        mockMvc.perform(post("/intra/tickets/" + r.getId() + "/status")
                        .param("status", RequestStatus.IN_PROGRESS.name())
                        .with(user(principal)))
                .andExpect(status().is3xxRedirection());

        client.onPush = events -> events.stream()
                .map(e -> new ResultatReception(e.syncId(), StatutReception.APPLIQUE, 3L)).toList();
        scheduler.pousser();

        assertThat(client.pushes).singleElement().satisfies(lot -> assertThat(lot).singleElement().satisfies(p -> {
            assertThat(p.status()).isEqualTo(RequestStatus.IN_PROGRESS);
            assertThat(p.publicComments()).extracting(RequestSyncPayload.PublicComment::content)
                    .anySatisfy(c -> assertThat(c).startsWith("Statut chang"));
            assertThat(p.publicComments()).allSatisfy(c -> assertThat(c.syncId()).isNotNull());
        }));
    }

    // ── Fix round 4 : plafond de tentatives ─────────────────────────────────

    /**
     * Echec DETERMINISTE (resultat ERREUR du maitre) qui atteint le plafond (3 dans cette classe) : statut
     * terminal, plus jamais retente. (Avant le correctif C-1 de la revue finale, ce test utilisait une
     * exception reseau : un echec de transport ne compte desormais plus vers le plafond.)
     */
    @Test
    void entryReachingMaxAttemptsBecomesDefinitiveFailureAndIsNeverRetried() {
        Request r = savedRequest("Rejet deterministe");
        SyncOutboxEntry entry = pendingEntry(r.getSyncId());
        entry.setStatut(SyncOutboxEntryStatut.ECHEC);
        entry.setTentatives(2); // max - 1
        entry.setDerniereTentativeA(Instant.now().minusSeconds(60));
        outboxRepository.saveAndFlush(entry);
        client.onPush = events -> events.stream()
                .map(e -> new ResultatReception(e.syncId(), StatutReception.ERREUR, null)).toList();

        scheduler.pousser();

        SyncOutboxEntry apres = reload(entry);
        assertThat(apres.getStatut()).isEqualTo(SyncOutboxEntryStatut.ECHEC_DEFINITIF);
        assertThat(apres.getTentatives()).isEqualTo(3);
        assertThat(apres.getDerniereTentativeA()).isNotNull();

        apres.setDerniereTentativeA(Instant.now().minusSeconds(3600));
        outboxRepository.saveAndFlush(apres);
        scheduler.pousser();
        assertThat(client.pushes).hasSize(1); // plus jamais repoussee
    }

    // ── Revue finale, C-1 : seuls les echecs DETERMINISTES comptent vers le plafond ──

    /**
     * Coupure reseau longue : bien plus d'echecs de TRANSPORT consecutifs que le plafond (3 ici), puis
     * reprise. L'edition doit finir livree (ENVOYE), jamais abandonnee en ECHEC_DEFINITIF.
     */
    @Test
    void longNetworkOutageNeverAbandonsTheEditAndItIsDeliveredOnRecovery() {
        Request r = savedRequest("Creee pendant la coupure");
        SyncOutboxEntry entry = pendingEntry(r.getSyncId());
        client.onPush = events -> { throw new ResourceAccessException("Connection refused"); };

        int echecs = 3 + 5; // max-tentatives + 5
        for (int i = 0; i < echecs; i++) {
            finDuBackoff(entry);
            scheduler.pousser();
            assertThat(reload(entry).getStatut()).isEqualTo(SyncOutboxEntryStatut.ECHEC);
        }
        assertThat(client.pushes).hasSize(echecs);
        assertThat(reload(entry).getTentatives()).isEqualTo(0); // transport : compteur fige (residu C-1)

        client.onPush = events -> events.stream()
                .map(e -> new ResultatReception(e.syncId(), StatutReception.CREE, 0L)).toList();
        finDuBackoff(entry);
        scheduler.pousser();

        assertThat(client.pushes).hasSize(echecs + 1);
        assertThat(reload(entry).getStatut()).isEqualTo(SyncOutboxEntryStatut.ENVOYE);
    }

    /**
     * Residu C-1 : coupure longue (echecs de TRANSPORT au-dela du plafond) puis UN SEUL echec deterministe
     * (ERREUR, ex. incident transitoire du maitre a son redemarrage). L'entree ne doit PAS basculer
     * immediatement en ECHEC_DEFINITIF : les echecs de transport ne consomment pas son quota.
     */
    @Test
    void singleDeterministicErrorAfterLongOutageDoesNotAbandonTheEditImmediately() {
        Request r = savedRequest("Creee pendant la coupure");
        SyncOutboxEntry entry = pendingEntry(r.getSyncId());
        client.onPush = events -> { throw new ResourceAccessException("Connection refused"); };
        for (int i = 0; i < 3 + 5; i++) {
            finDuBackoff(entry);
            scheduler.pousser();
        }

        client.onPush = events -> events.stream()
                .map(e -> new ResultatReception(e.syncId(), StatutReception.ERREUR, null)).toList();
        finDuBackoff(entry);
        scheduler.pousser();

        SyncOutboxEntry apres = reload(entry);
        assertThat(apres.getStatut()).isEqualTo(SyncOutboxEntryStatut.ECHEC);
        assertThat(apres.getTentatives()).isEqualTo(1);

        // Son propre quota de rejets deterministes reste intact : bascule seulement au 3e.
        for (int i = 2; i <= 3; i++) {
            finDuBackoff(entry);
            scheduler.pousser();
        }
        assertThat(reload(entry).getStatut()).isEqualTo(SyncOutboxEntryStatut.ECHEC_DEFINITIF);
    }

    /** Rejet deterministe repete (ERREUR) depuis zero : atteint ECHEC_DEFINITIF pile au plafond. */
    @Test
    void repeatedDeterministicErrorReachesDefinitiveFailureAtTheCap() {
        Request r = savedRequest("Rejete a chaque fois");
        SyncOutboxEntry entry = pendingEntry(r.getSyncId());
        client.onPush = events -> events.stream()
                .map(e -> new ResultatReception(e.syncId(), StatutReception.ERREUR, null)).toList();

        for (int i = 1; i <= 2; i++) {
            finDuBackoff(entry);
            scheduler.pousser();
            assertThat(reload(entry).getStatut()).isEqualTo(SyncOutboxEntryStatut.ECHEC);
        }
        finDuBackoff(entry);
        scheduler.pousser();

        SyncOutboxEntry apres = reload(entry);
        assertThat(apres.getStatut()).isEqualTo(SyncOutboxEntryStatut.ECHEC_DEFINITIF);
        assertThat(apres.getTentatives()).isEqualTo(3);
    }

    /** Entree sans Request locale ni payload fige : echec deterministe, atteint lui aussi le plafond. */
    @Test
    void entryWithoutLocalRequestReachesDefinitiveFailureAtTheCap() {
        SyncOutboxEntry entry = pendingEntry(UUID.randomUUID());

        for (int i = 0; i < 3; i++) {
            finDuBackoff(entry);
            scheduler.pousser();
        }

        assertThat(reload(entry).getStatut()).isEqualTo(SyncOutboxEntryStatut.ECHEC_DEFINITIF);
        assertThat(client.pushes).isEmpty();
    }

    /**
     * Backoff plafonne a {@value SyncOutboxScheduler#BACKOFF_MAX_SECONDES} s : meme apres un tres grand
     * nombre d'echecs de transport, une entree est retentee au plus toutes les 5 minutes.
     */
    @Test
    void backoffIntervalIsCappedEvenAfterManyTransportFailures() {
        Request r = savedRequest("Coupure tres longue");
        SyncOutboxEntry entry = pendingEntry(r.getSyncId());
        entry.setStatut(SyncOutboxEntryStatut.ECHEC);
        entry.setTentatives(1000); // backoff non plafonne : 5000 s
        entry.setDerniereTentativeA(Instant.now().minusSeconds(SyncOutboxScheduler.BACKOFF_MAX_SECONDES + 1));
        outboxRepository.saveAndFlush(entry);
        client.onPush = events -> events.stream()
                .map(e -> new ResultatReception(e.syncId(), StatutReception.APPLIQUE, 1L)).toList();

        scheduler.pousser();

        assertThat(client.pushes).hasSize(1);
        assertThat(reload(entry).getStatut()).isEqualTo(SyncOutboxEntryStatut.ENVOYE);
    }

    /** Sous le plafond, un echec reste un ECHEC ordinaire (retente avec backoff). */
    @Test
    void failureBelowMaxAttemptsStaysOrdinaryEchec() {
        Request r = savedRequest("Panne transitoire");
        SyncOutboxEntry entry = pendingEntry(r.getSyncId());
        entry.setStatut(SyncOutboxEntryStatut.ECHEC);
        entry.setTentatives(1);
        entry.setDerniereTentativeA(Instant.now().minusSeconds(60));
        outboxRepository.saveAndFlush(entry);
        client.onPush = events -> events.stream()
                .map(e -> new ResultatReception(e.syncId(), StatutReception.ERREUR, null)).toList();

        scheduler.pousser();

        SyncOutboxEntry apres = reload(entry);
        assertThat(apres.getStatut()).isEqualTo(SyncOutboxEntryStatut.ECHEC);
        assertThat(apres.getTentatives()).isEqualTo(2);
    }

    /**
     * Une fois l'unique edition en attente passee en ECHEC_DEFINITIF (rejet ERREUR du maitre), le pull
     * suivant s'applique IMMEDIATEMENT (etat maitre, base avancee) au lieu d'etre differe indefiniment.
     */
    @Test
    void pullIsAppliedImmediatelyOnceThePendingEditBecameDefinitiveFailure() {
        Request r = savedRequest("Etat maitre v3");
        knownMasterVersion(r.getSyncId(), 3L);
        UUID syncId = r.getSyncId();
        editViaListener(syncId, "Edition CLIENT rejetee");
        SyncOutboxEntry entry = entriesFor(syncId).get(0);
        entry.setStatut(SyncOutboxEntryStatut.ECHEC);
        entry.setTentatives(2);
        entry.setDerniereTentativeA(Instant.now().minusSeconds(60));
        outboxRepository.saveAndFlush(entry);
        client.onPush = events -> events.stream()
                .map(e -> new ResultatReception(e.syncId(), StatutReception.ERREUR, null)).toList();

        scheduler.pousser();
        assertThat(reload(entry).getStatut()).isEqualTo(SyncOutboxEntryStatut.ECHEC_DEFINITIF);

        client.onPull = since -> new SyncClient.PullResult(
                List.of(payload(syncId, 4L, "Etat maitre v4")), Instant.now());
        scheduler.tirer();

        entityManager.flush();
        entityManager.clear();
        Request apres = requestRepository.findBySyncId(syncId).orElseThrow();
        assertThat(apres.getTitle()).isEqualTo("Etat maitre v4");
        assertThat(apres.getStatus()).isEqualTo(RequestStatus.COMPLETED);
        SyncVersionConnue connue = versionRepository.findByEntitySyncId(syncId).orElseThrow();
        assertThat(connue.getPayloadPullDiffere()).isNull();
        assertThat(connue.getVersionMaitre()).isEqualTo(4L);
    }

    // ── Step 6 : pull + prevention de l'echo ────────────────────────────────

    @Test
    void pullAppliesReceivedPayloadAndDoesNotRequeueItForOutbox() {
        Request r = savedRequest("Avant pull");
        long outboxAvant = outboxRepository.count();
        client.onPull = since -> new SyncClient.PullResult(
                List.of(payload(r.getSyncId(), 12L, "Modifie par le partenaire")),
                Instant.parse("2026-10-27T10:00:00Z"));

        scheduler.tirer();

        entityManager.flush();
        entityManager.clear();
        Request apres = requestRepository.findBySyncId(r.getSyncId()).orElseThrow();
        assertThat(apres.getTitle()).isEqualTo("Modifie par le partenaire");
        assertThat(apres.getStatus()).isEqualTo(RequestStatus.COMPLETED);
        // Prevention d'echo : aucune entree d'outbox pour un changement RECU.
        assertThat(outboxRepository.count()).isEqualTo(outboxAvant);
        assertThat(entriesFor(r.getSyncId())).isEmpty();
        // La version maitre recue devient la version de base locale.
        assertThat(versionRepository.findByEntitySyncId(r.getSyncId()).orElseThrow().getVersionMaitre())
                .isEqualTo(12L);

        // Le curseur renvoye par le maitre est repasse au sondage suivant, recule de la marge de
        // recouvrement (revue finale I-3).
        scheduler.tirer();
        assertThat(client.pullSinces).containsExactly(null, Instant.parse("2026-10-27T10:00:00Z")
                .minusSeconds(SyncOutboxScheduler.MARGE_RECOUVREMENT_CURSEUR_SECONDES));
    }

    // ── Revue finale I-3 : marge de recouvrement sur le curseur envoye ───────

    /**
     * Le {@code since} ENVOYE est le curseur recu du maitre recule de la marge (une entree dont le commit
     * a ete visible apres un pull qui a deja avance le curseur au-dela de son {@code creeA} est ainsi
     * re-servie). Le curseur STOCKE reste la vraie valeur du maitre : pas de derive cumulative d'un
     * sondage a l'autre, et il avance normalement quand le maitre en renvoie un nouveau.
     */
    @Test
    void sentSinceIsStoredCursorMinusOverlapMarginAndStoredCursorStaysTheMasterValue() {
        Instant c1 = Instant.parse("2026-10-27T10:00:00Z");
        Instant c2 = Instant.parse("2026-10-27T10:05:00Z");
        long marge = SyncOutboxScheduler.MARGE_RECOUVREMENT_CURSEUR_SECONDES;
        assertThat(marge).isGreaterThanOrEqualTo(60);

        client.onPull = since -> new SyncClient.PullResult(List.of(), c1);
        scheduler.tirer(); // since = null (premier sondage), curseur stocke = c1
        client.onPull = since -> new SyncClient.PullResult(List.of(), null);
        scheduler.tirer(); // since = c1 - marge ; aucun curseur renvoye : stocke inchange
        scheduler.tirer(); // since = c1 - marge encore (et non c1 - 2 * marge)
        client.onPull = since -> new SyncClient.PullResult(List.of(), c2);
        scheduler.tirer(); // since = c1 - marge, curseur stocke = c2
        scheduler.tirer(); // since = c2 - marge

        assertThat(client.pullSinces).containsExactly(
                null,
                c1.minusSeconds(marge),
                c1.minusSeconds(marge),
                c1.minusSeconds(marge),
                c2.minusSeconds(marge));
    }

    // ── Fix round 2, I-2 : commentaires publics et assignation appliques a la reception ──

    @Test
    void pullAddsReceivedPublicCommentOnceAndStoresAssignmentText() {
        Request r = savedRequest("Avec commentaire");
        UUID syncId = r.getSyncId();
        UUID commentSyncId = UUID.randomUUID();
        LocalDateTime dateOrigine = LocalDateTime.of(2026, 10, 1, 10, 0);
        RequestSyncPayload recu = new RequestSyncPayload(syncId, 5L, RequestStatus.IN_PROGRESS, null,
                RequestPriority.MEDIUM, "Avec commentaire", null, "Equipe N2", "Jean Technicien",
                List.of(new RequestSyncPayload.PublicComment(commentSyncId, "Reponse du partenaire", dateOrigine)));
        client.onPull = since -> new SyncClient.PullResult(List.of(recu), null);

        scheduler.tirer();
        scheduler.tirer(); // meme payload recu deux fois : idempotent

        entityManager.flush();
        entityManager.clear();
        List<Comment> commentaires = commentsOf(syncId);
        assertThat(commentaires).singleElement().satisfies(c -> {
            assertThat(c.getSyncId()).isEqualTo(commentSyncId);
            assertThat(c.getContent()).isEqualTo("Reponse du partenaire");
            assertThat(c.isPublic()).isTrue();
            assertThat(c.getCreatedAt()).isEqualTo(dateOrigine);
        });
        Request apres = requestRepository.findBySyncId(syncId).orElseThrow();
        assertThat(apres.getSyncAssignedTeamName()).isEqualTo("Equipe N2");
        assertThat(apres.getSyncAssignedToName()).isEqualTo("Jean Technicien");
        assertThat(entriesFor(syncId)).isEmpty(); // prevention d'echo : ni Request ni commentaire
    }

    @Test
    void pullCreatesUnknownRequestWithSameSyncIdAndNoOutboxEntry() {
        UUID syncId = UUID.randomUUID();
        client.onPull = since -> new SyncClient.PullResult(
                List.of(payload(syncId, 0L, "Creee cote partenaire")), Instant.now());

        scheduler.tirer();

        Request creee = requestRepository.findBySyncId(syncId).orElseThrow();
        assertThat(creee.getTitle()).isEqualTo("Creee cote partenaire");
        assertThat(entriesFor(syncId)).isEmpty();
    }

    @Test
    void pullFailureDoesNotCrashNorAdvanceCursor() {
        client.onPull = since -> { throw new ResourceAccessException("Connection refused"); };

        assertThatCode(() -> scheduler.synchroniser()).doesNotThrowAnyException();
        assertThatCode(() -> scheduler.synchroniser()).doesNotThrowAnyException();

        assertThat(client.pullSinces).containsExactly(null, null);
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    /** Request sauvee SANS registerChange() : aucune entree d'outbox automatique. */
    private Request savedRequest(String title) {
        Request r = new Request();
        r.setTitle(title);
        return requestRepository.saveAndFlush(r);
    }

    /** Edition locale par le chemin reel : registerChange() + save -> listener -> entree figee. */
    private void editViaListener(UUID syncId, String title) {
        Request r = requestRepository.findBySyncId(syncId).orElseThrow();
        r.setTitle(title);
        r.registerChange();
        requestRepository.saveAndFlush(r);
    }

    /** Ecriture locale SANS evenement (comme une ecriture recue) : aucune entree d'outbox. */
    private void editTitleWithoutEvent(UUID syncId, String title) {
        Request r = requestRepository.findBySyncId(syncId).orElseThrow();
        r.setTitle(title);
        requestRepository.saveAndFlush(r);
    }

    private List<Comment> commentsOf(UUID requestSyncId) {
        Long requestId = requestRepository.findBySyncId(requestSyncId).orElseThrow().getId();
        return commentRepository.findAll().stream()
                .filter(c -> c.getRequest() != null && requestId.equals(c.getRequest().getId()))
                .toList();
    }

    private void editDescriptionViaListener(UUID syncId, String description) {
        Request r = requestRepository.findBySyncId(syncId).orElseThrow();
        r.setDescription(description);
        r.registerChange();
        requestRepository.saveAndFlush(r);
    }

    /** Utilisateur persiste + principal ADMIN (meme montage que RequestSyncListenerTest). */
    private CustomUserDetails persistedPrincipal() {
        Tenant tenant = new Tenant();
        tenant.setName("Sync Sched Tenant");
        tenant.setSchemaName("sync_sched_" + UUID.randomUUID().toString().replace("-", ""));
        tenant = tenantRepository.save(tenant);
        String email = "sync-sched-" + UUID.randomUUID() + "@logicielpourtous.com";
        User persisted = new User();
        persisted.setEmail(email);
        persisted.setPassword("test");
        persisted.setFullName("Sync Sched");
        persisted.setActive(true);
        persisted.setTenant(tenant);
        userRepository.save(persisted);
        User principalUser = new User();
        principalUser.setEmail(email);
        principalUser.setPassword("test");
        principalUser.setFullName("Sync Sched");
        principalUser.setActive(true);
        principalUser.setTenant(tenant);
        principalUser.setRoles(Set.of(new Role("ADMIN", "Admin", "ESN")));
        return new CustomUserDetails(principalUser);
    }

    private SyncOutboxEntry pendingEntry(UUID syncId) {
        SyncOutboxEntry e = new SyncOutboxEntry();
        e.setEntityType("Request");
        e.setEntitySyncId(syncId);
        e.setPayload("{}");
        return outboxRepository.saveAndFlush(e);
    }

    /** Fait comme si le backoff de l'entree etait ecoule (derniere tentative il y a une heure). */
    private void finDuBackoff(SyncOutboxEntry entry) {
        SyncOutboxEntry e = reload(entry);
        if (e.getDerniereTentativeA() != null) {
            e.setDerniereTentativeA(Instant.now().minusSeconds(3600));
            outboxRepository.saveAndFlush(e);
        }
    }

    private void knownMasterVersion(UUID syncId, long version) {
        SyncVersionConnue v = new SyncVersionConnue();
        v.setEntitySyncId(syncId);
        v.setVersionMaitre(version);
        versionRepository.saveAndFlush(v);
    }

    private SyncOutboxEntry reload(SyncOutboxEntry entry) {
        entityManager.flush();
        entityManager.clear();
        return outboxRepository.findById(entry.getId()).orElseThrow();
    }

    private List<SyncOutboxEntry> entriesFor(UUID syncId) {
        return outboxRepository.findAll().stream().filter(e -> syncId.equals(e.getEntitySyncId())).toList();
    }

    private static RequestSyncPayload payload(UUID syncId, long version, String title) {
        return new RequestSyncPayload(syncId, version, RequestStatus.COMPLETED, null, RequestPriority.LOW,
                title, "desc", null, null, List.of());
    }
}
