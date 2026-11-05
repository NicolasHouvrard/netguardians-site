package com.logicielpourtous.site.features.connector;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SyncLogRepository extends JpaRepository<SyncLog, Long> {

    List<SyncLog> findTop5ByConnectorIdOrderByStartedAtDesc(Long connectorId);

    Page<SyncLog> findAllByOrderByStartedAtDesc(Pageable pageable);

    Page<SyncLog> findByConnectorIdOrderByStartedAtDesc(Long connectorId, Pageable pageable);

    @Query("SELECT s FROM SyncLog s WHERE s.connector.id = :id ORDER BY s.startedAt DESC LIMIT 1")
    java.util.Optional<SyncLog> findLastByConnectorId(@Param("id") Long connectorId);

    long countByConnectorIdAndStatus(Long connectorId, SyncLog.Status status);

    @Query("SELECT COALESCE(SUM(s.created + s.updated), 0) FROM SyncLog s WHERE s.connector.id = :id AND s.status = 'SUCCESS'")
    long sumProductsSyncedByConnector(@Param("id") Long connectorId);
}
