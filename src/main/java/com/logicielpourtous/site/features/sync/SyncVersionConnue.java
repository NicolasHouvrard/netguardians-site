package com.logicielpourtous.site.features.sync;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Cote SECONDAIRE : derniere version MAITRE connue d'une entite synchronisee (par {@code syncId}).
 *
 * <p>Pourquoi : les compteurs {@code @Version} de deux bases distinctes sont independants (chaque
 * serveur incremente le sien a chaque UPDATE local, y compris quand il applique un changement recu).
 * Pour que le maitre puisse detecter un conflit, le secondaire doit lui envoyer la version MAITRE sur
 * laquelle repose sa modification (sa "version de base"), pas sa version locale. Cette version de base
 * est mise a jour a chaque payload recu par le pull, et a chaque push accepte par le maitre (qui
 * renvoie sa nouvelle version).
 *
 * <p>Entite purement locale (n'etend pas {@code Syncable}, ne traverse jamais le reseau).
 */
@Entity
@Table(name = "sync_versions_connues")
@Getter
@Setter
public class SyncVersionConnue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private UUID entitySyncId;

    /** Derniere version maitre connue ; {@code -1} = aucune (voir {@code SyncOutboxScheduler#differer}). */
    private long versionMaitre;

    /**
     * Payload RECU par le pull mais DIFFERE (JSON) : la {@code Request} avait des editions locales en
     * attente d'envoi, qu'il aurait ecrasees. Applique (ou ecarte s'il est perime) par
     * {@code SyncOutboxScheduler#appliquerPullsDifferes} une fois ces editions livrees. {@code null} = aucun.
     */
    @Column(columnDefinition = "TEXT")
    private String payloadPullDiffere;

    private Instant misAJourA;

    @PrePersist
    @PreUpdate
    protected void touch() {
        misAJourA = Instant.now();
    }
}
