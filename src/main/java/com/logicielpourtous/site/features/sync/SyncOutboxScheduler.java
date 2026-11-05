package com.logicielpourtous.site.features.sync;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logicielpourtous.site.features.request.Request;
import com.logicielpourtous.site.features.request.RequestRepository;
import com.logicielpourtous.site.features.sync.SyncOutboxEntry.SyncOutboxEntryStatut;
import com.logicielpourtous.site.features.sync.SyncReceptionService.ResultatReception;

/**
 * Cote SECONDAIRE uniquement (bean cree seulement si {@code app.sync.master-url} est configure) :
 * job planifie qui, a chaque cycle, (1) pousse l'outbox locale vers le maitre puis (2) sonde le
 * maitre pour appliquer ses changements. Toujours initie par le secondaire.
 *
 * <p><b>Invariant central (fix round 3)</b> : tant qu'une {@code Request} a des editions locales EN
 * ATTENTE d'envoi (entrees {@code EN_ATTENTE}/{@code ECHEC}), un pull ne remplace JAMAIS ses champs
 * locaux : le payload recu est mis de cote ({@link SyncVersionConnue#getPayloadPullDiffere()}) et
 * applique seulement une fois ces editions livrees ({@link #appliquerPullsDifferes()}). Consequence :
 * l'etat local d'une {@code Request} avec des editions en attente est toujours CUMULATIF (toutes ces
 * editions, sur la base maitre connue), donc :
 * <ul>
 *   <li>le DERNIER payload fige contient toutes les editions en attente : un seul envoi par
 *       {@code Request}, sans risque de perdre une edition anterieure ;</li>
 *   <li>la version de base a pousser est la version maitre connue COURANTE : elle n'a pu avancer, depuis
 *       ces editions, que par nos propres push (le pull etant differe).</li>
 * </ul>
 * Les rounds precedents laissaient le pull ecraser l'etat local puis tentaient de compenser au push :
 * deux pertes silencieuses en ont resulte (I-1, I-A). Ici l'ecrasement n'a simplement plus lieu.
 *
 * <p><b>Robustesse</b> : aucune exception ne sort d'un cycle.
 *
 * <p><b>Backoff</b> (push) : une entree en {@code ECHEC} n'est retentee qu'apres
 * {@code min(tentatives * }{@value #BACKOFF_SECONDES_PAR_TENTATIVE}{@code , }{@value #BACKOFF_MAX_SECONDES}
 * {@code ) s} depuis son dernier echec (intervalle plafonne : meme apres une tres longue coupure, une entree
 * est retentee au moins toutes les 5 minutes) ; les entrees suivantes de la meme {@code Request} attendent
 * avec elle.
 *
 * <p><b>Plafond de tentatives (fix round 4, restreint par la revue finale C-1)</b> : l'invariant central a
 * un revers non borne — une entree rejetee de facon deterministe par le maitre (payload invalide, refus...)
 * resterait {@code ECHEC} pour toujours et bloquerait pour toujours les pulls de sa {@code Request}
 * (divergence silencieuse). On distingue donc deux classes d'echec, au point d'appel :
 * <ul>
 *   <li><b>Transport</b> (exception pendant {@code client.push()} : reseau coupe, maitre eteint, timeout) :
 *       TRANSITOIRE, ne compte JAMAIS vers le plafond — retente indefiniment (backoff plafonne). Une coupure
 *       reseau, meme longue, ne fait donc jamais abandonner une edition (ni une {@code Request} creee
 *       pendant la coupure).</li>
 *   <li><b>Deterministe</b> (le maitre a recu le lot et renvoie {@code ERREUR}, {@code REFUSE} ou aucun
 *       resultat pour ce {@code syncId} ; ou entree dont la {@code Request} locale a disparu) : retenter le
 *       meme payload ne changera rien. Quand un tel echec porte {@code tentatives} a
 *       {@code app.sync.max-tentatives} (defaut 20), l'entree passe en {@code ECHEC_DEFINITIF}.</li>
 * </ul>
 * Seule la seconde classe incremente {@code tentatives} (donc le backoff et le plafond) : un echec de
 * transport met a jour {@code derniereTentativeA} mais laisse {@code tentatives} FIGE. Une coupure ne
 * consomme donc jamais le quota de rejets deterministes d'une entree (sinon le premier rejet apres une
 * longue coupure, meme un {@code ERREUR} transitoire du maitre, l'abandonnerait aussitot), au prix d'un
 * appel HTTP par cycle de sondage pendant la coupure tant que l'entree n'a eu aucun rejet deterministe
 * (backoff a la valeur courante, plafonnee). {@code ECHEC_DEFINITIF} est
 * un statut TERMINAL, jamais retente, HORS de l'ensemble des statuts "en attente d'envoi". Consequence
 * voulue : l'edition locale est ABANDONNEE (log {@code ERROR} explicite, seul signal pour l'instant) et, des
 * que la {@code Request} n'a plus d'autre entree {@code EN_ATTENTE}/{@code ECHEC}, ses pulls (differes ou
 * nouveaux) s'appliquent normalement : l'etat du maitre ecrase l'edition jamais livree. Abandon explicite
 * plutot que blocage silencieux.
 *
 * <p><b>Curseur du pull</b> : conserve EN MEMOIRE. Apres un redemarrage, le premier sondage repart
 * sans {@code since} : les entrees en attente pour ce secondaire sont renvoyees et reappliquees
 * (idempotent : chaque payload reflete l'etat courant cote maitre ; differe si editions locales en attente).
 *
 * <p>Proprietes : {@code app.sync.poll-interval-ms} (delai entre deux cycles, defaut 5000) et
 * {@code app.sync.initial-delay-ms} (defaut 5000), {@code app.sync.max-tentatives} (plafond d'echecs
 * DETERMINISTES avant {@code ECHEC_DEFINITIF}, defaut 20).
 */
@Component
@ConditionalOnProperty(name = "app.sync.master-url")
public class SyncOutboxScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncOutboxScheduler.class);

    static final long BACKOFF_SECONDES_PAR_TENTATIVE = 5;

    /** Intervalle maximal entre deux tentatives d'une meme entree, quel que soit son nombre d'echecs. */
    static final long BACKOFF_MAX_SECONDES = 300;

    /**
     * Recul applique au curseur ENVOYE au maitre (jamais au curseur stocke) : voir {@link #tirer()}.
     */
    static final long MARGE_RECOUVREMENT_CURSEUR_SECONDES = 60;

    private static final Set<SyncOutboxEntryStatut> EN_ATTENTE_D_ENVOI =
            EnumSet.of(SyncOutboxEntryStatut.EN_ATTENTE, SyncOutboxEntryStatut.ECHEC);

    private final SyncClient client;
    private final SyncOutboxEntryRepository outboxRepository;
    private final SyncVersionConnueRepository versionRepository;
    private final RequestRepository requestRepository;
    private final RequestSyncPayloadMapper mapper;
    private final RequestSyncApplier applier;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final int maxTentatives;

    private volatile Instant curseur;

    public SyncOutboxScheduler(SyncClient client,
                               SyncOutboxEntryRepository outboxRepository,
                               SyncVersionConnueRepository versionRepository,
                               RequestRepository requestRepository,
                               RequestSyncPayloadMapper mapper,
                               RequestSyncApplier applier,
                               ObjectMapper objectMapper,
                               PlatformTransactionManager transactionManager,
                               @Value("${app.sync.max-tentatives:20}") int maxTentatives) {
        this.maxTentatives = maxTentatives;
        this.client = client;
        this.outboxRepository = outboxRepository;
        this.versionRepository = versionRepository;
        this.requestRepository = requestRepository;
        this.mapper = mapper;
        this.applier = applier;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${app.sync.poll-interval-ms:5000}",
               initialDelayString = "${app.sync.initial-delay-ms:5000}")
    public synchronized void synchroniser() {
        try {
            pousser();
        } catch (RuntimeException e) {
            log.error("Sync : cycle de push interrompu", e);
        }
        try {
            tirer();
        } catch (RuntimeException e) {
            log.error("Sync : cycle de pull interrompu", e);
        }
    }

    // ── Push ─────────────────────────────────────────────────────────────────

    /** Un envoi = le payload pousse pour UNE {@code Request}, couvrant toutes ses entrees eligibles. */
    private record Envoi(RequestSyncPayload payload, List<Long> entryIds) {}

    /**
     * Pousse, pour chaque {@code Request} ayant des entrees eligibles, son DERNIER payload fige (par
     * {@link RequestSyncListener}, a l'instant de l'edition) — cumulatif grace a l'invariant de classe —
     * avec la version maitre connue courante. Toutes ses entrees prennent le statut du resultat.
     *
     * <p>Ancienne entree sans payload fige (marqueur, ou echec du figeage) : repli sur l'etat courant de
     * la {@code Request}, lui aussi cumulatif.
     */
    synchronized void pousser() {
        Instant maintenant = Instant.now();
        Map<UUID, List<SyncOutboxEntry>> parSyncId = new LinkedHashMap<>();
        Set<UUID> bloques = new HashSet<>();
        for (SyncOutboxEntry e : outboxRepository.findByDestinationServerIdIsNullAndStatutInOrderByCreeAAscIdAsc(
                EN_ATTENTE_D_ENVOI)) {
            if (bloques.contains(e.getEntitySyncId())) {
                continue;
            }
            if (!eligible(e, maintenant)) {
                // Ordre preserve : les entrees suivantes de cette Request attendent la fin du backoff.
                bloques.add(e.getEntitySyncId());
                parSyncId.remove(e.getEntitySyncId());
                continue;
            }
            parSyncId.computeIfAbsent(e.getEntitySyncId(), k -> new ArrayList<>()).add(e);
        }
        if (parSyncId.isEmpty()) {
            appliquerPullsDifferes();
            return;
        }

        List<Envoi> envois = new ArrayList<>();
        List<Long> introuvables = new ArrayList<>();
        tx.executeWithoutResult(status -> {
            for (Map.Entry<UUID, List<SyncOutboxEntry>> groupe : parSyncId.entrySet()) {
                Envoi envoi = preparerEnvoi(groupe.getKey(), groupe.getValue());
                if (envoi == null) {
                    groupe.getValue().forEach(e -> introuvables.add(e.getId()));
                } else {
                    envois.add(envoi);
                }
            }
        });
        if (!introuvables.isEmpty()) {
            log.error("Sync : {} entree(s) d'outbox sans Request locale (supprimee ?), marquee(s) ECHEC : {}",
                    introuvables.size(), introuvables);
            marquerEchec(introuvables, maintenant, true); // deterministe : la Request ne reviendra pas
        }
        if (envois.isEmpty()) {
            return;
        }

        List<ResultatReception> resultats;
        try {
            resultats = client.push(envois.stream().map(Envoi::payload).toList());
        } catch (RuntimeException e) {
            log.warn("Sync : push vers le maitre en echec ({} evenement(s)), nouvel essai avec backoff : {}",
                    envois.size(), e.toString());
            List<Long> ids = new ArrayList<>();
            envois.forEach(envoi -> ids.addAll(envoi.entryIds()));
            // Transport (reseau coupe, maitre eteint, timeout) : transitoire, ne compte JAMAIS vers le plafond.
            marquerEchec(ids, maintenant, false);
            return;
        }

        tx.executeWithoutResult(status -> {
            for (int i = 0; i < envois.size(); i++) {
                // Une issue par evenement, dans l'ordre du lot.
                ResultatReception resultat = resultats != null && i < resultats.size() ? resultats.get(i) : null;
                traiterResultat(envois.get(i), resultat, maintenant);
            }
        });
        appliquerPullsDifferes();
    }

    /** {@code null} si la {@code Request} n'existe plus localement et qu'aucun payload n'est fige. */
    private Envoi preparerEnvoi(UUID syncId, List<SyncOutboxEntry> entrees) {
        Long baseCourante = syncId == null ? null
                : versionRepository.findByEntitySyncId(syncId).map(SyncVersionConnue::getVersionMaitre)
                        .filter(v -> v >= 0) // -1 = aucune base connue (voir differer)
                        .orElse(null);
        List<Long> ids = entrees.stream().map(SyncOutboxEntry::getId).toList();

        RequestSyncPayload dernierFige = lireFige(entrees.get(entrees.size() - 1));
        if (dernierFige != null) {
            // Base courante si connue ; sinon (Request creee ici, jamais vue du maitre) version figee.
            return new Envoi(baseCourante != null ? dernierFige.avecVersion(baseCourante) : dernierFige, ids);
        }
        Optional<Request> request = syncId == null ? Optional.empty() : requestRepository.findBySyncId(syncId);
        if (request.isEmpty()) {
            return null;
        }
        return new Envoi(mapper.toPayload(request.get(),
                baseCourante != null ? baseCourante : request.get().getVersion()), ids);
    }

    private RequestSyncPayload lireFige(SyncOutboxEntry entree) {
        String json = entree.getPayload();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            RequestSyncPayload payload = objectMapper.readValue(json, RequestSyncPayload.class);
            if (payload == null || payload.version() == null
                    || !Objects.equals(payload.syncId(), entree.getEntitySyncId())) {
                return null;
            }
            return payload;
        } catch (JsonProcessingException e) {
            return null; // marqueur d'avant le figeage : repli sur l'etat courant
        }
    }

    private void traiterResultat(Envoi envoi, ResultatReception resultat, Instant maintenant) {
        RequestSyncPayload payload = envoi.payload();
        List<Long> ids = envoi.entryIds();
        if (resultat == null || resultat.statut() == null || !Objects.equals(resultat.syncId(), payload.syncId())) {
            log.warn("Sync : aucun resultat du maitre pour {}, nouvel essai plus tard", payload.syncId());
            marquer(ids, SyncOutboxEntryStatut.ECHEC, maintenant, true);
            return;
        }
        // Ici le maitre a recu et traite le lot : un ERREUR/REFUSE est un echec DETERMINISTE (compte
        // vers le plafond), renvoyer le meme payload ne changera pas le resultat.
        switch (resultat.statut()) {
            case CREE, APPLIQUE -> {
                if (resultat.version() != null) {
                    enregistrerVersionMaitre(payload.syncId(), resultat.version());
                }
                marquer(ids, SyncOutboxEntryStatut.ENVOYE, maintenant, false);
            }
            case CONFLIT -> {
                // Livre : le conflit est enregistre (avec ce payload) et a trancher cote maitre. Le
                // renvoyer ne ferait que recreer le meme conflit. L'etat maitre arrivera par le pull
                // (eventuellement deja differe : applique juste apres, voir appliquerPullsDifferes).
                log.warn("Sync : conflit signale par le maitre pour {} (version envoyee {}, version maitre {})",
                        payload.syncId(), payload.version(), resultat.version());
                marquer(ids, SyncOutboxEntryStatut.ENVOYE, maintenant, false);
            }
            case ERREUR -> marquer(ids, SyncOutboxEntryStatut.ECHEC, maintenant, true);
            case REFUSE -> {
                log.error("Sync : le maitre refuse la modification de {} (hors du client de ce serveur)",
                        payload.syncId());
                marquer(ids, SyncOutboxEntryStatut.ECHEC, maintenant, true);
            }
        }
    }

    private static boolean eligible(SyncOutboxEntry e, Instant maintenant) {
        if (e.getStatut() != SyncOutboxEntryStatut.ECHEC || e.getDerniereTentativeA() == null) {
            return true;
        }
        Instant prochaine = e.getDerniereTentativeA()
                .plusSeconds(Math.min(e.getTentatives() * BACKOFF_SECONDES_PAR_TENTATIVE, BACKOFF_MAX_SECONDES));
        return !prochaine.isAfter(maintenant);
    }

    private void marquerEchec(List<Long> ids, Instant maintenant, boolean deterministe) {
        tx.executeWithoutResult(status -> marquer(ids, SyncOutboxEntryStatut.ECHEC, maintenant, deterministe));
    }

    /**
     * Point unique de tous les chemins d'echec. Un {@code ECHEC} met toujours a jour
     * {@code derniereTentativeA} ; seul un echec {@code deterministe} incremente {@code tentatives} et peut,
     * au plafond ({@code maxTentatives}), devenir {@code ECHEC_DEFINITIF}. Un echec de transport laisse
     * {@code tentatives} inchange et n'y mene jamais (voir Javadoc de classe).
     *
     * @param deterministe {@code true} si le maitre a recu et rejete l'evenement (ou si la {@code Request}
     *        locale a disparu) : retenter le meme payload ne changera rien. Ignore hors {@code ECHEC}.
     */
    private void marquer(List<Long> ids, SyncOutboxEntryStatut statut, Instant maintenant, boolean deterministe) {
        for (SyncOutboxEntry e : outboxRepository.findAllById(ids)) {
            e.setStatut(statut);
            if (statut == SyncOutboxEntryStatut.ECHEC) {
                if (deterministe) {
                    // Transport : compteur FIGE, sinon une longue coupure consommerait d'avance le quota
                    // de rejets deterministes (residu C-1).
                    e.setTentatives(e.getTentatives() + 1);
                }
                e.setDerniereTentativeA(maintenant);
                if (deterministe && e.getTentatives() >= maxTentatives) {
                    e.setStatut(SyncOutboxEntryStatut.ECHEC_DEFINITIF);
                    log.error("Sync : entree d'outbox {} pour {} abandonnee apres {} tentative(s) (plafond "
                                    + "app.sync.max-tentatives={}) -> ECHEC_DEFINITIF. Cette edition locale ne sera "
                                    + "PLUS JAMAIS poussee vers le maitre ; les pulls de cette Request reprennent "
                                    + "immediatement et l'etat du maitre ecrasera l'edition non livree.",
                            e.getId(), e.getEntitySyncId(), e.getTentatives(), maxTentatives);
                }
            }
            outboxRepository.save(e);
        }
    }

    // ── Pull ─────────────────────────────────────────────────────────────────

    /**
     * Sonde le maitre et applique chaque payload recu (le secondaire applique ce qu'il recoit, sans
     * detection de conflit de son cote) — DIFFERE si la {@code Request} a des editions locales en attente
     * (invariant de classe). Ecriture via {@link RequestSyncApplier}, sans evenement de domaine : aucune
     * entree d'outbox n'est creee (prevention d'echo).
     *
     * <p>Le curseur n'avance que si tout le lot a ete traite : en cas d'echec, le lot entier est re-sonde
     * au cycle suivant (reapplication idempotente).
     *
     * <p><b>Marge de recouvrement (revue finale I-3)</b> : le {@code since} ENVOYE est le curseur stocke
     * recule de {@value #MARGE_RECOUVREMENT_CURSEUR_SECONDES} s. Le {@code creeA} d'une entree d'outbox
     * maitre est fixe AVANT son commit ; deux ecritures concurrentes peuvent committer dans l'ordre inverse
     * de leurs {@code creeA}, et un pull intercale entre les deux commits avancerait le curseur au-dela de
     * l'entree la plus ancienne, encore invisible — filtree ensuite pour toujours par
     * {@code creeA > since}. Reculer le {@code since} la fait re-servir au sondage suivant. Sans danger :
     * l'application d'un payload recu est idempotente (champs ecrases par l'etat COURANT du maitre,
     * commentaires ajoutes par {@code syncId} sans doublon — voir {@link RequestSyncApplier} et le test
     * {@code pullAddsReceivedPublicCommentOnceAndStoresAssignmentText} ; differe si editions locales en
     * attente, le plus recent l'emportant), comme deja pour le re-sondage complet apres redemarrage. Seule
     * la valeur ENVOYEE est reculee : le curseur STOCKE reste la vraie valeur du maitre (pas de derive).
     */
    synchronized void tirer() {
        appliquerPullsDifferes();
        SyncClient.PullResult resultat;
        try {
            Instant since = curseur == null ? null : curseur.minusSeconds(MARGE_RECOUVREMENT_CURSEUR_SECONDES);
            resultat = client.pull(since);
        } catch (RuntimeException e) {
            log.warn("Sync : sondage du maitre en echec, nouvel essai au prochain cycle : {}", e.toString());
            return;
        }
        if (resultat == null || resultat.events() == null) {
            return;
        }
        for (RequestSyncPayload payload : resultat.events()) {
            if (payload == null || payload.syncId() == null || payload.version() == null) {
                log.error("Sync : payload recu du maitre sans syncId ou sans version, ignore : {}", payload);
                continue;
            }
            try {
                tx.executeWithoutResult(status -> appliquerRecu(payload));
            } catch (RuntimeException e) {
                log.error("Sync : echec de l'application de {} recu du maitre, lot re-sonde au prochain cycle",
                        payload.syncId(), e);
                return;
            }
        }
        if (resultat.cursor() != null) {
            curseur = resultat.cursor();
        }
    }

    private void appliquerRecu(RequestSyncPayload payload) {
        Optional<Request> existante = requestRepository.findBySyncId(payload.syncId());
        if (existante.isPresent() && editionsLocalesEnAttente(payload.syncId())) {
            // Commentaires : ajouts purs, sans risque d'ecraser une edition locale -> appliques tout de suite.
            applier.ajouterCommentairesManquants(existante.get(), payload);
            differer(payload);
            return;
        }
        Request appliquee = existante
                .map(request -> applier.appliquer(request, payload))
                .orElseGet(() -> applier.creer(payload, null));
        applier.appliquerAssignation(appliquee, payload); // le maitre fait autorite sur l'assignation
        enregistrerVersionMaitre(payload.syncId(), payload.version());
    }

    private boolean editionsLocalesEnAttente(UUID syncId) {
        return outboxRepository.existsByEntitySyncIdAndDestinationServerIdIsNullAndStatutIn(syncId, EN_ATTENTE_D_ENVOI);
    }

    /** Met de cote le payload recu (le plus recent l'emporte). La version de base n'avance PAS. */
    private void differer(RequestSyncPayload payload) {
        SyncVersionConnue connue = versionConnue(payload.syncId());
        RequestSyncPayload dejaDiffere = lireDiffere(connue);
        if (dejaDiffere != null && dejaDiffere.version() != null && dejaDiffere.version() > payload.version()) {
            return;
        }
        try {
            connue.setPayloadPullDiffere(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Serialisation du payload differe impossible", e);
        }
        if (connue.getId() == null) {
            // Aucune base maitre connue (cas limite : Request creee ici, pas encore livree). Sentinelle -1
            // = "inconnue" : ignoree au push (version figee utilisee), et tout payload differe la depasse.
            connue.setVersionMaitre(-1);
        }
        versionRepository.save(connue);
        log.info("Sync : {} recu du maitre (version {}) differe, editions locales en attente d'envoi",
                payload.syncId(), payload.version());
    }

    /**
     * Applique les payloads differes dont la {@code Request} n'a plus d'edition locale en attente :
     * <ul>
     *   <li>version differee &gt; version de base connue : le maitre a avance au-dela de ce que nos editions
     *       connaissaient (typiquement : notre push a ete enregistre en conflit) -> on applique l'etat maitre ;</li>
     *   <li>sinon : notre push a ete applique par-dessus (base deja avancee au-dela) -> payload perime, ecarte.</li>
     * </ul>
     */
    void appliquerPullsDifferes() {
        tx.executeWithoutResult(status -> {
            for (SyncVersionConnue connue : versionRepository.findByPayloadPullDiffereIsNotNull()) {
                if (editionsLocalesEnAttente(connue.getEntitySyncId())) {
                    continue;
                }
                RequestSyncPayload differe = lireDiffere(connue);
                connue.setPayloadPullDiffere(null);
                if (differe != null && differe.version() != null && differe.version() > connue.getVersionMaitre()) {
                    requestRepository.findBySyncId(differe.syncId()).ifPresent(request -> {
                        Request appliquee = applier.appliquer(request, differe);
                        applier.appliquerAssignation(appliquee, differe);
                    });
                    connue.setVersionMaitre(differe.version());
                }
                versionRepository.save(connue);
            }
        });
    }

    private RequestSyncPayload lireDiffere(SyncVersionConnue connue) {
        if (connue.getPayloadPullDiffere() == null) {
            return null;
        }
        try {
            return objectMapper.readValue(connue.getPayloadPullDiffere(), RequestSyncPayload.class);
        } catch (JsonProcessingException e) {
            log.error("Sync : payload differe illisible pour {}, ecarte", connue.getEntitySyncId());
            return null;
        }
    }

    private SyncVersionConnue versionConnue(UUID syncId) {
        return versionRepository.findByEntitySyncId(syncId).orElseGet(() -> {
            SyncVersionConnue v = new SyncVersionConnue();
            v.setEntitySyncId(syncId);
            return v;
        });
    }

    private void enregistrerVersionMaitre(UUID syncId, long versionMaitre) {
        SyncVersionConnue connue = versionConnue(syncId);
        connue.setVersionMaitre(versionMaitre);
        versionRepository.save(connue);
    }

    /** Remet le curseur du pull a zero (tests). */
    void reinitialiserCurseur() {
        curseur = null;
    }
}
