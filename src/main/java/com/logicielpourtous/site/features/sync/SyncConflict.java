package com.logicielpourtous.site.features.sync;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Conflit de synchronisation detecte cote partenaire/maitre : la version recue
 * d'une entite synchronisable ne correspond pas a la version actuelle en base.
 *
 * <p>Entite purement locale, detection UNIQUEMENT cote partenaire/maitre (autorite
 * de resolution) — le secondaire applique toujours ce qu'il recoit sans detecter
 * de conflit de son cote (Task 4).
 */
@Entity
@Table(name = "sync_conflicts")
@Getter
@Setter
public class SyncConflict {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Correspond au {@code syncId} de l'entite en conflit. */
    private UUID entitySyncId;

    private long versionAttendue;

    private long versionRecue;

    @Column(columnDefinition = "TEXT")
    private String payloadRecu;

    private boolean resolu;

    private Instant creeA;

    @PrePersist
    protected void onCreate() {
        creeA = Instant.now();
    }
}
