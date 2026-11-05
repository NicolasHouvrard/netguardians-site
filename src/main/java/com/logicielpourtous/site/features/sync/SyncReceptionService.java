package com.logicielpourtous.site.features.sync;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logicielpourtous.site.features.request.Request;
import com.logicielpourtous.site.features.request.RequestRepository;

/**
 * Cote MAITRE : reception d'un evenement pousse par un secondaire, AVEC detection de conflit
 * prealable. Detection uniquement ici (autorite de resolution) : le secondaire, lui, applique
 * toujours ce qu'il recoit.
 *
 * <p>Une transaction par evenement (methode publique appelee depuis le controleur, qui n'est pas
 * transactionnel) : un evenement en conflit ou en erreur n'annule pas les autres du lot.
 */
@Service
public class SyncReceptionService {

    private static final Logger log = LoggerFactory.getLogger(SyncReceptionService.class);

    /** Issue de la reception d'un evenement, renvoyee au secondaire. */
    public enum StatutReception {
        /** Inconnue ici : creee avec le {@code syncId} recu. */
        CREE,
        /** Version a jour : changement applique. */
        APPLIQUE,
        /** Version perimee (ou modification concurrente) : NON applique, {@link SyncConflict} cree. */
        CONFLIT,
        /** Erreur inattendue : rien d'applique, le secondaire doit reessayer plus tard. */
        ERREUR,
        /**
         * Demande d'un AUTRE client que celui du serveur appelant (ou serveur rattache a aucun client) :
         * rien d'applique pour cet evenement, les autres evenements du lot sont traites normalement. Seul
         * un serveur rattache a aucun client fait refuser le lot entier en 403
         * ({@link #verifierAutorisation}).
         */
        REFUSE
    }

    /**
     * @param version version maitre de la {@code Request} APRES traitement ({@code null} si
     *        {@link StatutReception#ERREUR}). Sur {@code CREE}/{@code APPLIQUE}, le secondaire en fait
     *        sa nouvelle version de base.
     */
    public record ResultatReception(UUID syncId, StatutReception statut, Long version) {}

    private final RequestRepository requestRepository;
    private final SyncConflictRepository conflictRepository;
    private final SecondaryServerRepository secondaryServerRepository;
    private final RequestSyncApplier applier;
    private final ObjectMapper objectMapper;

    public SyncReceptionService(RequestRepository requestRepository, SyncConflictRepository conflictRepository,
                                SecondaryServerRepository secondaryServerRepository,
                                RequestSyncApplier applier, ObjectMapper objectMapper) {
        this.requestRepository = requestRepository;
        this.conflictRepository = conflictRepository;
        this.secondaryServerRepository = secondaryServerRepository;
        this.applier = applier;
        this.objectMapper = objectMapper;
    }

    /**
     * Controle d'acces GLOBAL du lot, avant toute ecriture : le serveur appelant (authentifie par sa cle
     * API) doit etre rattache a un client, sinon il est inutilisable et tout le lot est refuse.
     *
     * <p>Le cloisonnement PAR DEMANDE (une demande existante visee doit appartenir au client du serveur
     * appelant) n'est volontairement PAS fait ici mais evenement par evenement dans {@link #recevoir}, qui
     * renvoie {@link StatutReception#REFUSE} sans rien ecrire pour cet evenement. Revue finale I-1 : le
     * faire ici refusait le lot ENTIER pour une seule demande hors-client (typiquement reaffectee a un autre
     * client par le maitre), bloquant a chaque cycle toutes les autres editions du lot, sans rapport.
     *
     * @return {@code true} si le serveur appelant est rattache a un client
     */
    @Transactional(readOnly = true)
    public boolean verifierAutorisation(Long serverId, List<RequestSyncPayload> events) {
        if (customerIdDuServeur(serverId) == null) {
            log.warn("Sync : push refuse, le secondaire {} n'est rattache a aucun client", serverId);
            return false;
        }
        return true;
    }

    /**
     * Client ({@code Customer}) auquel est rattache le serveur secondaire {@code serverId}, ou {@code null}
     * (serveur inconnu ou rattache a aucun client). Utilise aussi au pull pour re-verifier, au moment de
     * servir, que chaque demande appartient toujours au client du serveur appelant.
     */
    @Transactional(readOnly = true)
    public Long customerIdDuServeur(Long serverId) {
        return serverId == null ? null
                : secondaryServerRepository.findById(serverId).map(SecondaryServer::getCustomerId).orElse(null);
    }

    /**
     * Compare {@code payload.version} a la version ACTUELLE de la {@code Request} (resolue par
     * {@code syncId}) AVANT toute ecriture. Differente : {@link SyncConflict}, la {@code Request} n'est
     * pas touchee — sauf si le payload est deja identique a l'etat actuel (renvoi d'un evenement deja
     * applique dont la reponse a ete perdue) : rien a trancher, aucune ecriture, pas de faux conflit.
     * Egale : application (le {@code UPDATE ... WHERE version = ?} d'Hibernate protege en
     * plus contre une ecriture locale concurrente entre la lecture et le flush : voir
     * {@link #enregistrerConflitConcurrent}).
     *
     * <p>La comparaison est explicite : jamais de {@code setVersion(payload.version())} pour "forcer"
     * la version recue.
     */
    @Transactional
    public ResultatReception recevoir(Long serverId, RequestSyncPayload payload) {
        Long customerId = customerIdDuServeur(serverId);
        if (customerId == null) {
            return new ResultatReception(payload.syncId(), StatutReception.REFUSE, null);
        }
        Optional<Request> existante = requestRepository.findBySyncId(payload.syncId());
        if (existante.isPresent() && !customerId.equals(existante.get().customerId())) {
            // Cloisonnement par evenement (seul controle par demande, voir verifierAutorisation).
            log.warn("Sync : Request {} hors du client {} du secondaire {}, non appliquee",
                    payload.syncId(), customerId, serverId);
            return new ResultatReception(payload.syncId(), StatutReception.REFUSE, null);
        }
        if (existante.isEmpty()) {
            Request creee = applier.creer(payload, customerId);
            log.info("Sync : Request {} creee depuis le secondaire {}", payload.syncId(), serverId);
            return new ResultatReception(payload.syncId(), StatutReception.CREE, creee.getVersion());
        }

        Request request = existante.get();
        // Commentaires publics : ajouts purs, sans conflit possible, appliques dans tous les cas
        // (idempotent). Seuls les champs de la demande sont soumis a la detection de conflit.
        applier.ajouterCommentairesManquants(request, payload);
        long versionActuelle = request.getVersion();
        if (versionActuelle != payload.version()) {
            if (applier.dejaAJour(request, payload)) {
                // Rien a trancher : typiquement le renvoi d'un evenement deja applique dont la
                // reponse a ete perdue. Aucune ecriture, la version actuelle est renvoyee.
                return new ResultatReception(payload.syncId(), StatutReception.APPLIQUE, versionActuelle);
            }
            enregistrerConflit(payload, versionActuelle);
            log.warn("Sync : conflit sur Request {} (secondaire {}) : version recue {}, version actuelle {}",
                    payload.syncId(), serverId, payload.version(), versionActuelle);
            return new ResultatReception(payload.syncId(), StatutReception.CONFLIT, versionActuelle);
        }

        Request appliquee = applier.appliquer(request, payload);
        return new ResultatReception(payload.syncId(), StatutReception.APPLIQUE, appliquee.getVersion());
    }

    /**
     * Course concurrente : la version etait a jour a la lecture, mais une ecriture locale l'a
     * incrementee avant le flush ({@code ObjectOptimisticLockingFailureException}, transaction de
     * {@link #recevoir} deja annulee). Meme traitement qu'une version perimee, dans une nouvelle
     * transaction.
     */
    @Transactional
    public ResultatReception enregistrerConflitConcurrent(RequestSyncPayload payload) {
        long versionActuelle = requestRepository.findBySyncId(payload.syncId())
                .map(Request::getVersion)
                .orElse(-1L);
        enregistrerConflit(payload, versionActuelle);
        return new ResultatReception(payload.syncId(), StatutReception.CONFLIT, versionActuelle);
    }

    private void enregistrerConflit(RequestSyncPayload payload, long versionActuelle) {
        SyncConflict conflit = new SyncConflict();
        conflit.setEntitySyncId(payload.syncId());
        conflit.setVersionAttendue(versionActuelle);
        conflit.setVersionRecue(payload.version());
        conflit.setPayloadRecu(serialiser(payload));
        conflictRepository.save(conflit);
    }

    private String serialiser(RequestSyncPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // Le payload vient d'etre desserialise : ne devrait pas arriver. On garde une trace
            // minimale plutot que de perdre le conflit.
            return String.valueOf(payload);
        }
    }
}
