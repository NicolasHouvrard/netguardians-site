package com.logicielpourtous.site.features.sync;

import java.util.Objects;

import org.springframework.stereotype.Component;

import com.logicielpourtous.site.features.request.Comment;
import com.logicielpourtous.site.features.request.CommentRepository;
import com.logicielpourtous.site.features.request.Request;
import com.logicielpourtous.site.features.request.RequestCustomerLink;
import com.logicielpourtous.site.features.request.RequestRepository;

/**
 * Ecrit en base un {@link RequestSyncPayload} RECU d'un autre serveur (maitre a la reception d'un
 * push, secondaire a l'application d'un pull).
 *
 * <p><b>Prevention d'echo</b> : n'appelle JAMAIS {@code Request.registerChange()} (ni
 * {@code Comment.registerAdded()}). Spring Data ne publie donc aucun evenement de domaine au
 * {@code save()}, {@link RequestSyncListener} ne cree aucune entree d'outbox : un changement recu
 * n'est jamais re-emis vers son serveur d'origine (sinon aller-retour infini client/partenaire).
 *
 * <p><b>Versions</b> : n'appelle JAMAIS {@code Syncable.setVersion(...)}. La version locale reste
 * geree par Hibernate ; la comparaison avec la version recue (detection de conflit) est faite
 * explicitement par l'appelant, avant d'appeler ce composant.
 *
 * <p>Appliques : statut, type, priorite, titre, description ; commentaires publics manquants (par
 * {@code syncId} de commentaire, idempotent) ; assignation en texte via {@link #appliquerAssignation},
 * cote secondaire uniquement (le maitre fait autorite sur l'assignation reelle).
 */
@Component
public class RequestSyncApplier {

    private final RequestRepository requestRepository;
    private final RequestCustomerLink customerLink;
    private final CommentRepository commentRepository;

    public RequestSyncApplier(RequestRepository requestRepository, RequestCustomerLink customerLink,
                              CommentRepository commentRepository) {
        this.requestRepository = requestRepository;
        this.customerLink = customerLink;
        this.commentRepository = commentRepository;
    }

    /**
     * Applique le payload (champs + commentaires publics manquants) a une {@code Request} existante. A
     * appeler dans une transaction.
     */
    public Request appliquer(Request cible, RequestSyncPayload payload) {
        copierChamps(payload, cible);
        Request sauvee = requestRepository.saveAndFlush(cible); // aucun registerChange() : aucun evenement
        ajouterCommentairesManquants(sauvee, payload);
        return sauvee;
    }

    /**
     * Cree localement les commentaires publics du payload absents d'ici (par {@code syncId} de
     * commentaire : un meme commentaire peut etre recu plusieurs fois). Un commentaire est un ajout pur,
     * sans conflit possible : le maitre l'appelle aussi quand les champs de la demande sont en conflit.
     * Aucun {@code registerAdded()} : pas d'evenement, pas d'echo. Auteur non transmis ({@code author}
     * {@code null}, comme un commentaire venu d'un email) ; date d'origine conservee.
     */
    public void ajouterCommentairesManquants(Request cible, RequestSyncPayload payload) {
        if (payload.publicComments() == null) {
            return;
        }
        for (RequestSyncPayload.PublicComment recu : payload.publicComments()) {
            if (recu == null || recu.syncId() == null || recu.content() == null
                    || commentRepository.existsBySyncId(recu.syncId())) {
                continue;
            }
            Comment comment = new Comment();
            comment.adopterSyncIdRecu(recu.syncId());
            comment.setContent(recu.content());
            comment.setPublic(true); // seuls les commentaires publics voyagent
            comment.setCreatedAt(recu.createdAt());
            comment.setRequest(cible);
            commentRepository.save(comment);
        }
    }

    /**
     * Cote SECONDAIRE uniquement : stocke l'assignation recue du maitre (equipe / technicien) en texte,
     * ces entites n'existant pas ici. Jamais appele cote maitre, ou l'assignation reelle fait autorite.
     */
    public void appliquerAssignation(Request cible, RequestSyncPayload payload) {
        cible.setSyncAssignedTeamName(payload.assignedTeamName());
        cible.setSyncAssignedToName(payload.assignedToName());
        requestRepository.saveAndFlush(cible);
    }

    /**
     * Cree la copie locale d'une {@code Request} inconnue ici, avec le {@code syncId} recu.
     *
     * @param customerId client auquel rattacher la demande ({@code null} = aucun). Cote maitre : TOUJOURS
     *        le client du serveur secondaire appelant, jamais une valeur venue du payload. Cote
     *        secondaire (pull) : {@code null}, les donnees client ne voyagent pas.
     */
    public Request creer(RequestSyncPayload payload, Long customerId) {
        Request nouvelle = new Request();
        nouvelle.adopterSyncIdRecu(payload.syncId());
        if (customerId != null) {
            customerLink.rattacher(nouvelle, customerId);
        }
        copierChamps(payload, nouvelle);
        Request creee = requestRepository.saveAndFlush(nouvelle); // aucun registerChange() : aucun evenement
        ajouterCommentairesManquants(creee, payload);
        return creee;
    }

    /** {@code true} si l'application du payload ne changerait aucun des champs synchronises. */
    public boolean dejaAJour(Request cible, RequestSyncPayload payload) {
        return cible.getStatus() == payload.status()
                && cible.getType() == payload.type()
                && cible.getPriority() == payload.priority()
                && Objects.equals(cible.getTitle(), payload.title())
                && Objects.equals(cible.getDescription(), payload.description());
    }

    private static void copierChamps(RequestSyncPayload payload, Request cible) {
        cible.setStatus(payload.status());
        cible.setType(payload.type());
        cible.setPriority(payload.priority());
        cible.setTitle(payload.title());
        cible.setDescription(payload.description());
    }
}
