package com.logicielpourtous.site.features.sync;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Entree d'outbox locale representant un changement a transmettre au reseau de
 * synchronisation (push effectue par {@code SyncOutboxScheduler}, Task 4).
 *
 * <p>Entite purement locale : ne traverse jamais le reseau elle-meme ({@code payload}
 * contient la representation serialisee de l'entite synchronisable concernee).
 */
@Entity
@Table(name = "sync_outbox_entries")
@Getter
@Setter
public class SyncOutboxEntry {

    public enum SyncOutboxEntryStatut {
        EN_ATTENTE,
        ENVOYE,
        /** Echec d'envoi retentable (backoff). Compte comme edition locale en attente. */
        ECHEC,
        /**
         * Statut TERMINAL : plafond de tentatives ({@code app.sync.max-tentatives}) atteint. L'entree n'est
         * plus jamais retentee et ne compte PLUS comme edition locale en attente : l'edition est abandonnee
         * et les pulls de la {@code Request} concernee reprennent (voir {@code SyncOutboxScheduler}).
         */
        ECHEC_DEFINITIF
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Type de l'entite synchronisee ({@code "Request"} pour l'instant). */
    private String entityType;

    /** Correspond a {@code Request.syncId} (ou syncId de l'entite {@code entityType} concernee). */
    private UUID entitySyncId;

    @Column(columnDefinition = "TEXT")
    private String payload;

    /**
     * Cote maitre : id du {@link SecondaryServer} destinataire de cette entree, a qui
     * {@code GET /api/sync/events} la renvoie. {@code null} = entree sans destinataire secondaire
     * (cas du secondaire, dont l'outbox est poussee vers SON maitre par {@code SyncOutboxScheduler}).
     * Renseigne par {@link RequestSyncListener} (regle de routage : voir sa Javadoc).
     */
    private Long destinationServerId;

    @Enumerated(EnumType.STRING)
    private SyncOutboxEntryStatut statut = SyncOutboxEntryStatut.EN_ATTENTE;

    /** Nombre d'echecs d'envoi (push du secondaire), base du backoff de {@code SyncOutboxScheduler}. */
    private int tentatives;

    /** Horodatage du dernier echec d'envoi ({@code null} si jamais tente en echec). */
    private Instant derniereTentativeA;

    private Instant creeA;

    @PrePersist
    protected void onCreate() {
        if (statut == null) {
            statut = SyncOutboxEntryStatut.EN_ATTENTE;
        }
        creeA = Instant.now();
    }
}
