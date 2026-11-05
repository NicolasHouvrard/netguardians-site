package com.logicielpourtous.site.features.sync;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.logicielpourtous.site.features.request.Comment;
import com.logicielpourtous.site.features.request.Request;

/**
 * SEUL point de construction d'un {@link RequestSyncPayload} (emission reseau, dans les deux sens :
 * pull servi par le maitre, push d'un secondaire).
 *
 * <p><b>Filtrage des commentaires (Review Focus)</b> : filtre EXPLICITE sur {@link Comment#isPublic()} ;
 * un commentaire prive ne quitte jamais le serveur, quel que soit le sens.
 *
 * <p>A appeler dans une transaction : equipe, technicien et commentaires sont LAZY.
 */
@Component
public class RequestSyncPayloadMapper {

    /** Payload portant la version JPA LOCALE de la {@code Request} (cas du maitre, qui fait autorite). */
    public RequestSyncPayload toPayload(Request request) {
        return toPayload(request, Objects.requireNonNull(request.getVersion(),
                "Request sans version (jamais persistee ?) : impossible de la synchroniser"));
    }

    /**
     * Payload portant une version de base explicite. Utilise par le secondaire au push : la version
     * envoyee est la derniere version MAITRE connue de cette {@code Request} (voir
     * {@link SyncVersionConnue}), pas sa version locale — les compteurs {@code @Version} de deux bases
     * distinctes sont independants, comparer une version locale de secondaire a celle du maitre
     * declarerait un conflit a chaque edition.
     */
    public RequestSyncPayload toPayload(Request request, long version) {
        return toPayload(request, version, request.getComments());
    }

    /**
     * Idem avec une liste de commentaires explicite (lue par requete par l'appelant) : la collection
     * {@code request.getComments()} d'une instance geree peut etre perimee (commentaire persiste sans etre
     * ajoute a la collection, cas des commentaires "journal" de {@code TicketController}). Meme filtre
     * {@link Comment#isPublic()}.
     */
    public RequestSyncPayload toPayload(Request request, long version, List<Comment> comments) {
        List<RequestSyncPayload.PublicComment> publicComments = comments.stream()
                .filter(Comment::isPublic)
                .map(c -> new RequestSyncPayload.PublicComment(c.getSyncId(), c.getContent(), c.getCreatedAt()))
                .toList();
        return new RequestSyncPayload(
                request.getSyncId(),
                version,
                request.getStatus(),
                request.getType(),
                request.getPriority(),
                request.getTitle(),
                request.getDescription(),
                request.assignedTeamDisplayName(),
                request.assignedToDisplayName(),
                publicComments);
    }
}
