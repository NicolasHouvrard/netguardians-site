package com.logicielpourtous.site.features.sync;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface SyncConflictRepository extends JpaRepository<SyncConflict, Long> {

    /**
     * Liste "a trancher" de l'ecran {@code /intra/licence/conflits} (module {@code licence}, Task 5) :
     * conflits pas encore resolus.
     */
    List<SyncConflict> findByResolu(boolean resolu);

    /**
     * Verrou pessimiste : deux resolutions concurrentes du MEME conflit (double-clic, ou "appliquer"
     * et "garder l'etat actuel" cliques presque simultanement sur 2 onglets) ne peuvent pas toutes les
     * deux s'executer — la 2e, apres avoir attendu que la 1ere committe, relit {@code resolu=true} et
     * n'agit plus. A appeler dans une transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from SyncConflict c where c.id = :id")
    Optional<SyncConflict> findByIdForUpdate(@Param("id") Long id);
}
