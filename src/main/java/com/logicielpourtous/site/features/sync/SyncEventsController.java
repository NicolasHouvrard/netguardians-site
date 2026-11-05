package com.logicielpourtous.site.features.sync;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.logicielpourtous.site.features.request.Request;
import com.logicielpourtous.site.features.request.RequestRepository;
import com.logicielpourtous.site.features.sync.SyncOutboxEntry.SyncOutboxEntryStatut;

/**
 * Endpoints cote MAITRE, toujours appeles par un secondaire (jamais l'inverse), authentifies par
 * {@link SyncApiKeyAuthFilter} : le principal est l'id du {@link SecondaryServer} appelant.
 */
@RestController
public class SyncEventsController {

    private static final Logger log = LoggerFactory.getLogger(SyncEventsController.class);

    /**
     * En-tete de reponse du pull : {@code creeA} de la derniere entree renvoyee, a repasser tel quel
     * en {@code since} au sondage suivant (curseur fourni par le maitre, insensible au decalage
     * d'horloge du secondaire). Absent si aucune entree n'est renvoyee.
     */
    public static final String CURSOR_HEADER = "X-Sync-Cursor";

    private final SyncOutboxEntryRepository outboxRepository;
    private final RequestRepository requestRepository;
    private final RequestSyncPayloadMapper mapper;
    private final SyncReceptionService receptionService;

    public SyncEventsController(SyncOutboxEntryRepository outboxRepository, RequestRepository requestRepository,
                                RequestSyncPayloadMapper mapper, SyncReceptionService receptionService) {
        this.outboxRepository = outboxRepository;
        this.requestRepository = requestRepository;
        this.mapper = mapper;
        this.receptionService = receptionService;
    }

    /** Reponse du push : une issue par evenement recu, dans l'ordre du lot. */
    public record PushResponse(List<SyncReceptionService.ResultatReception> resultats) {}

    /**
     * Reception des changements pousses par un secondaire, avec detection de conflit
     * ({@link SyncReceptionService#recevoir}). Repond 200 meme en cas de conflit : l'evenement est
     * LIVRE (le conflit est a trancher cote maitre, le secondaire ne doit pas le renvoyer en boucle).
     *
     * <p>403 sans rien ecrire si le serveur appelant n'est rattache a aucun client
     * ({@link SyncReceptionService#verifierAutorisation}). Une demande visee qui appartient a un autre
     * client ne bloque PAS le lot : cet evenement seul est {@code REFUSE} sans ecriture
     * ({@link SyncReceptionService#recevoir}), les autres sont traites normalement.
     *
     * <p>400 sans rien ecrire si un evenement du lot n'a pas de {@code syncId} ou de {@code version}
     * (un payload sans version ne doit jamais passer pour une "version 0"). Chaque evenement est traite
     * dans sa propre transaction (cette methode n'est volontairement pas {@code @Transactional}).
     */
    @PostMapping("/api/sync/events")
    public ResponseEntity<?> pushEvents(@AuthenticationPrincipal Long serverId,
                                        @RequestBody List<RequestSyncPayload> events) {
        for (RequestSyncPayload event : events) {
            if (event == null || event.syncId() == null || event.version() == null) {
                log.warn("Push sync du secondaire {} refuse : evenement sans syncId ou sans version", serverId);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("erreur", "Chaque evenement doit porter un syncId et une version"));
            }
        }

        if (!receptionService.verifierAutorisation(serverId, events)) {
            // Serveur rattache a aucun client : lot refuse EN BLOC, rien n'est ecrit. 403 et non 200, le
            // secondaire ne doit pas croire ses changements livres.
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("erreur", "Ce serveur n'est rattache a aucun client"));
        }

        List<SyncReceptionService.ResultatReception> resultats = new ArrayList<>(events.size());
        for (RequestSyncPayload event : events) {
            resultats.add(recevoir(serverId, event));
        }
        return ResponseEntity.ok(new PushResponse(resultats));
    }

    private SyncReceptionService.ResultatReception recevoir(Long serverId, RequestSyncPayload event) {
        try {
            return receptionService.recevoir(serverId, event);
        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("Sync : ecriture locale concurrente sur Request {} pendant la reception, conflit enregistre",
                    event.syncId());
            return receptionService.enregistrerConflitConcurrent(event);
        } catch (RuntimeException e) {
            log.error("Sync : echec de la reception de la Request {} (secondaire {})", event.syncId(), serverId, e);
            return new SyncReceptionService.ResultatReception(
                    event.syncId(), SyncReceptionService.StatutReception.ERREUR, null);
        }
    }

    /**
     * Changements en attente destines au secondaire appelant UNIQUEMENT, crees apres {@code since}
     * (optionnel), dans l'ordre de creation. Toutes les entrees sont renvoyees, meme plusieurs pour
     * la meme {@code Request} (aucune deduplication). Lecture seule : ne change pas le statut des
     * entrees, la progression est portee par le curseur {@value #CURSOR_HEADER}.
     *
     * <p>Chaque payload reflete l'etat COURANT de la {@code Request} (resolue par {@code syncId}) au
     * moment du pull, pas un instantane pris a l'ecriture de l'entree : le payload stocke dans
     * l'outbox n'est aujourd'hui qu'un marqueur minimal (Task 2). Une entree dont la {@code Request}
     * n'existe plus est ignoree.
     *
     * <p>Cloisonnement re-verifie AU MOMENT DE SERVIR (revue finale I-2) : une entree dont la
     * {@code Request} n'appartient plus au client du serveur appelant (reaffectee depuis le routage de
     * l'entree), ou dont le serveur appelant n'est rattache a aucun client, est ignoree. Le curseur avance
     * quand meme au-dela des entrees ignorees.
     */
    @GetMapping("/api/sync/events")
    @Transactional(readOnly = true)
    public ResponseEntity<List<RequestSyncPayload>> pullEvents(
            @AuthenticationPrincipal Long serverId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
        List<SyncOutboxEntry> entries = since == null
                ? outboxRepository.findByDestinationServerIdAndStatutOrderByCreeAAscIdAsc(
                        serverId, SyncOutboxEntryStatut.EN_ATTENTE)
                : outboxRepository.findByDestinationServerIdAndStatutAndCreeAAfterOrderByCreeAAscIdAsc(
                        serverId, SyncOutboxEntryStatut.EN_ATTENTE, since);

        Long customerIdAppelant = receptionService.customerIdDuServeur(serverId);
        List<RequestSyncPayload> payloads = new ArrayList<>(entries.size());
        for (SyncOutboxEntry entry : entries) {
            Optional<Request> request = requestRepository.findBySyncId(entry.getEntitySyncId());
            if (request.isEmpty()) {
                log.warn("Pull sync : entree outbox {} ignoree, Request syncId={} introuvable",
                        entry.getId(), entry.getEntitySyncId());
                continue;
            }
            if (customerIdAppelant == null || !customerIdAppelant.equals(request.get().customerId())) {
                // Routee vers ce serveur a l'epoque, mais la Request n'appartient plus (ou pas) a son
                // client (reaffectation entre-temps) : ne jamais servir le contenu d'un autre client.
                log.warn("Pull sync : entree outbox {} ignoree, Request syncId={} hors du client {} du "
                                + "secondaire {} (reaffectee ? client actuel : {})",
                        entry.getId(), entry.getEntitySyncId(), customerIdAppelant, serverId,
                        request.get().customerId());
                continue;
            }
            payloads.add(mapper.toPayload(request.get()));
        }

        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (!entries.isEmpty()) {
            response.header(CURSOR_HEADER, entries.get(entries.size() - 1).getCreeA().toString());
        }
        return response.body(payloads);
    }
}
