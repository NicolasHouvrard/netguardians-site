package com.logicielpourtous.site.features.sync;

import java.time.Instant;
import java.util.List;

/**
 * Cote SECONDAIRE : appels REST vers le maitre ({@code app.sync.master-url}), toujours inities par
 * le secondaire. Interface (implementation reelle : {@link RestSyncClient}) pour permettre de
 * substituer le transport dans les tests du {@link SyncOutboxScheduler}.
 *
 * <p>Toute erreur reseau ou HTTP non-2xx remonte en {@link RuntimeException} : c'est l'appelant
 * (le scheduler) qui la transforme en {@code ECHEC} + backoff, sans jamais laisser fuir l'exception.
 */
public interface SyncClient {

    /** {@code POST /api/sync/events} : une issue par evenement, dans l'ordre du lot. */
    List<SyncReceptionService.ResultatReception> push(List<RequestSyncPayload> events);

    /** {@code GET /api/sync/events?since=} ({@code since} optionnel). */
    PullResult pull(Instant since);

    /**
     * @param cursor valeur de l'en-tete {@link SyncEventsController#CURSOR_HEADER}, a repasser en
     *        {@code since} au sondage suivant ; {@code null} si aucune entree n'a ete renvoyee.
     */
    record PullResult(List<RequestSyncPayload> events, Instant cursor) {}
}
