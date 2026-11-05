package com.logicielpourtous.site.features.sync;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.logicielpourtous.site.features.request.Request;
import com.logicielpourtous.site.features.request.RequestPriority;
import com.logicielpourtous.site.features.request.RequestStatus;
import com.logicielpourtous.site.features.request.RequestType;

/**
 * Projection reseau explicite d'une {@link Request} (jamais l'entite JPA brute).
 *
 * <p>Les references non-{@code Syncable} (equipe, technicien) sont aplaties en texte d'affichage.
 * {@code publicComments} ne contient QUE les commentaires {@code isPublic=true} : les commentaires
 * prives ne quittent jamais le serveur. Seul constructeur legitime : {@link RequestSyncPayloadMapper}.
 *
 * @param version version MAITRE de la {@code Request} : sa version JPA ({@code @Version}) quand le
 *        maitre emet, la derniere version maitre connue quand un secondaire pousse (base de la
 *        detection de conflit). <b>Jamais {@code null}</b> dans un payload produit par
 *        {@link RequestSyncPayloadMapper}. Reste un {@code Long} et non
 *        un {@code long} pour la RECEPTION (Task 4) : selon sa configuration, Jackson desserialise un primitif absent
 *        du JSON en {@code 0} sans erreur, ce qui ferait passer un payload sans version pour un
 *        payload "version 0" ; avec {@code Long}, l'absence reste visible ({@code null}) et rejetable.
 */
public record RequestSyncPayload(
        UUID syncId,
        Long version,
        RequestStatus status,
        RequestType type,
        RequestPriority priority,
        String title,
        String description,
        String assignedTeamName,
        String assignedToName,
        List<PublicComment> publicComments) {

    /** Meme payload, autre version (rebasage par le scheduler, voir {@code SyncOutboxScheduler}). */
    public RequestSyncPayload avecVersion(Long nouvelleVersion) {
        return new RequestSyncPayload(syncId, nouvelleVersion, status, type, priority, title, description,
                assignedTeamName, assignedToName, publicComments);
    }

    /** @param syncId cle de synchronisation du commentaire (idempotence a la reception) */
    public record PublicComment(UUID syncId, String content, LocalDateTime createdAt) {}
}
