package com.logicielpourtous.site.features.sync;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncVersionConnueRepository extends JpaRepository<SyncVersionConnue, Long> {

    Optional<SyncVersionConnue> findByEntitySyncId(UUID entitySyncId);

    /** Payloads recus par le pull et differes (editions locales alors en attente). */
    List<SyncVersionConnue> findByPayloadPullDiffereIsNotNull();
}
