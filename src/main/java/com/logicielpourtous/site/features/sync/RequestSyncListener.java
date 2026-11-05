package com.logicielpourtous.site.features.sync;

import java.util.List;
import java.util.UUID;

import org.hibernate.LazyInitializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.logicielpourtous.site.features.commun.ServerRole;
import com.logicielpourtous.site.features.request.Comment;
import com.logicielpourtous.site.features.request.CommentAddedEvent;
import com.logicielpourtous.site.features.request.CommentRepository;
import com.logicielpourtous.site.features.request.Request;
import com.logicielpourtous.site.features.request.RequestChangedEvent;
import com.logicielpourtous.site.features.request.RequestRepository;

/**
 * Alimente l'outbox de synchronisation a partir des evenements de domaine publies par
 * Spring Data au {@code save()} d'une {@link Request} ({@link RequestChangedEvent}) ou d'un
 * {@link Comment} nouvellement cree ({@link CommentAddedEvent}).
 *
 * <p><b>Payload de l'entree</b> :
 * <ul>
 *   <li>Serveur SECONDAIRE : le {@link RequestSyncPayload} complet, FIGE ici (JSON), a partir de l'etat
 *       de la {@code Request} A CET INSTANT et de la version maitre connue a cet instant (version de base
 *       de l'edition). Le scheduler pousse ce payload fige, jamais un etat recalcule plus tard : sinon un
 *       pull arrive entre un push en echec et son reessai ecraserait l'edition locale, qui serait perdue
 *       sans trace (fix round 2, I-1).</li>
 *   <li>Serveur MAITRE : simple marqueur. Le pull sert volontairement l'etat COURANT du maitre (qui fait
 *       autorite) : un etat fige plus ancien n'apporterait rien au secondaire.</li>
 * </ul>
 * Filtrage des commentaires prives : par {@link RequestSyncPayloadMapper} dans les deux cas.
 *
 * <p><b>Routage (destinataire de chaque entree)</b> :
 * <ul>
 *   <li>Serveur SECONDAIRE ({@code app.sync.master-url} configure) : UNE entree sans destinataire
 *       ({@code destinationServerId = null}), poussee vers le maitre par {@link SyncOutboxScheduler}.</li>
 *   <li>Serveur MAITRE/partenaire : UNE entree PAR {@link SecondaryServer} de role {@code CLIENT} au
 *       statut {@code ACTIF} RATTACHE AU CLIENT ({@code Customer}) DE LA DEMANDE (servie a chacun par
 *       {@code GET /api/sync/events}). Demande sans client, ou aucun serveur actif pour ce client :
 *       aucune entree. Jamais de diffusion a tous les clients (divulgation entre clients).</li>
 * </ul>
 */
@Component
public class RequestSyncListener {

    private static final Logger log = LoggerFactory.getLogger(RequestSyncListener.class);

    static final String ENTITY_TYPE_REQUEST = "Request";

    private final SyncOutboxEntryRepository outboxRepository;
    private final RequestRepository requestRepository;
    private final SecondaryServerRepository secondaryServerRepository;
    private final SyncVersionConnueRepository versionRepository;
    private final CommentRepository commentRepository;
    private final RequestSyncPayloadMapper mapper;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final boolean secondaire;

    public RequestSyncListener(SyncOutboxEntryRepository outboxRepository,
                               RequestRepository requestRepository,
                               SecondaryServerRepository secondaryServerRepository,
                               SyncVersionConnueRepository versionRepository,
                               CommentRepository commentRepository,
                               RequestSyncPayloadMapper mapper,
                               ObjectMapper objectMapper,
                               PlatformTransactionManager transactionManager,
                               @Value("${app.sync.master-url:}") String masterUrl) {
        this.outboxRepository = outboxRepository;
        this.requestRepository = requestRepository;
        this.secondaryServerRepository = secondaryServerRepository;
        this.versionRepository = versionRepository;
        this.commentRepository = commentRepository;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
        this.secondaire = masterUrl != null && !masterUrl.isBlank();
    }

    @EventListener
    public void onRequestChanged(RequestChangedEvent event) {
        Request request = event.request();
        enqueue(resolveSyncId(request), resolveCustomerId(request),
                "{\"event\":\"RequestChanged\",\"requestId\":" + request.getId() + "}");
    }

    @EventListener
    public void onCommentAdded(CommentAddedEvent event) {
        Comment comment = event.comment();
        Request request = comment.getRequest();
        enqueue(resolveSyncId(request), resolveCustomerId(request),
                "{\"event\":\"CommentAdded\",\"requestId\":" + (request != null ? request.getId() : null)
                        + ",\"commentId\":" + comment.getId() + "}");
    }

    /**
     * Le {@code syncId} porte par l'instance de l'evenement peut etre {@code null} quand
     * {@code save()} a fait un {@code merge} d'une instance detachee qui ne l'a jamais porte
     * (ex. {@code @ModelAttribute Request} lie depuis un formulaire d'edition : {@code syncId}
     * n'a pas de setter, il n'est donc jamais lie, et l'evenement reference l'instance passee a
     * {@code save()}, pas le resultat du merge). On relit alors la valeur PERSISTEE par une
     * requete scalaire : un {@code findById} pourrait renvoyer l'instance geree, dont le
     * {@code syncId} en memoire a ete ecrase a {@code null} par ce meme merge.
     *
     * <p>Meme repli quand {@code request} est un proxy Hibernate LAZY detache, jamais initialise,
     * dont la session est fermee (ex. {@code MailMessage::getRequest} dans {@code MailService}, qui
     * tourne sans transaction) : {@code getSyncId()} n'y renvoie pas {@code null}, il LEVE
     * {@link LazyInitializationException}. Verifie empiriquement : c'est bien cette exception
     * Hibernate brute qui sort de l'appel ici (la {@code JpaSystemException} observee par
     * l'appelant n'est que sa traduction par Spring a la frontiere du {@code commentRepository.save()}).
     * L'id d'un proxy, lui, est toujours disponible sans initialisation.
     */
    private UUID resolveSyncId(Request request) {
        if (request == null) {
            return null;
        }
        UUID syncId;
        try {
            syncId = request.getSyncId();
        } catch (LazyInitializationException e) {
            syncId = null; // proxy detache non initialisable : repli par l'id ci-dessous
        }
        if (syncId != null) {
            return syncId;
        }
        if (request.getId() == null) {
            return null;
        }
        return requestRepository.findSyncIdById(request.getId()).orElse(null);
    }

    /**
     * Client de la demande, pour le routage. Valeur en memoire si accessible et non nulle ; sinon
     * (proxy LAZY detache non initialisable, instance liee depuis un formulaire...) valeur PERSISTEE,
     * relue par requete scalaire — meme raisonnement que {@link #resolveSyncId}.
     */
    private Long resolveCustomerId(Request request) {
        if (request == null) {
            return null;
        }
        Long customerId;
        try {
            customerId = request.customerId();
        } catch (LazyInitializationException e) {
            customerId = null;
        }
        if (customerId != null || request.getId() == null) {
            return customerId;
        }
        return requestRepository.findCustomerIdById(request.getId()).orElse(null);
    }

    private void enqueue(UUID syncId, Long customerId, String payload) {
        if (syncId == null) {
            // Listener synchrone, execute DANS le save() de l'appelant : lever une exception
            // ferait echouer l'action utilisateur (creation de ticket, commentaire...). On
            // journalise bruyamment plutot que de casser l'ecriture metier.
            log.error("Sync outbox : syncId introuvable, entree non creee (payload={})", payload);
            return;
        }
        if (secondaire) {
            String fige = payloadFige(syncId);
            outboxRepository.save(entry(syncId, fige != null ? fige : payload, null));
            return;
        }
        if (customerId == null) {
            // Demande rattachee a aucun client : aucun serveur secondaire n'est concerne.
            return;
        }
        List<SecondaryServer> destinataires = secondaryServerRepository.findByRoleAndStatutAndCustomerId(
                ServerRole.CLIENT, SecondaryServer.SecondaryServerStatut.ACTIF, customerId);
        for (SecondaryServer destinataire : destinataires) {
            outboxRepository.save(entry(syncId, payload, destinataire.getId()));
        }
    }

    /**
     * Cote secondaire : payload complet fige a l'instant de l'edition (voir Javadoc de classe). Relit la
     * {@code Request} par {@code syncId} dans une transaction (rejoint celle de l'appelant s'il y en a une,
     * sinon en ouvre une : l'instance de l'evenement peut etre un proxy detache ou une instance liee a un
     * formulaire, inutilisables pour lire les commentaires LAZY).
     *
     * <p>Ne leve jamais : listener synchrone execute dans le {@code save()} de l'appelant. Toute erreur est
     * attrapee DANS le callback (sinon {@link TransactionTemplate} marquerait la transaction de l'appelant
     * rollback-only) ; on renvoie alors {@code null} et l'entree garde le marqueur : le scheduler repliera
     * sur l'etat courant (comportement d'avant le fix).
     */
    private String payloadFige(UUID syncId) {
        return tx.execute(status -> {
            try {
                return requestRepository.findBySyncId(syncId).map(request -> {
                    long base = versionRepository.findByEntitySyncId(syncId)
                            .map(SyncVersionConnue::getVersionMaitre)
                            .filter(v -> v >= 0) // -1 = aucune base connue
                            .orElse(request.getVersion()); // jamais vue du maitre : version locale
                    try {
                        // Commentaires relus par REQUETE, pas request.getComments() : avec l'OSIV, la
                        // Request relue ici est la MEME instance geree que celle du controleur, dont la
                        // collection peut ignorer un commentaire tout juste persiste (fix round 3, I-B).
                        return objectMapper.writeValueAsString(mapper.toPayload(request, base,
                                commentRepository.findByRequestId(request.getId())));
                    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                        throw new IllegalStateException(e);
                    }
                }).orElse(null);
            } catch (RuntimeException e) {
                log.error("Sync outbox : payload non fige pour {}, repli sur l'etat courant au push", syncId, e);
                return null;
            }
        });
    }

    private static SyncOutboxEntry entry(UUID syncId, String payload, Long destinationServerId) {
        SyncOutboxEntry entry = new SyncOutboxEntry();
        entry.setEntityType(ENTITY_TYPE_REQUEST);
        entry.setEntitySyncId(syncId);
        entry.setPayload(payload);
        entry.setDestinationServerId(destinationServerId);
        return entry;
    }
}
