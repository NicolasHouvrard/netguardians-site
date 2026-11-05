package com.logicielpourtous.site.features.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findByTypeInOrderByTimestampDesc(List<AuditLog.Type> types, Pageable pageable);

    List<AuditLog> findAllByOrderByTimestampDesc(Pageable pageable);

    @Query("""
        SELECT a FROM AuditLog a
        WHERE (:type IS NULL OR a.type = :type)
          AND (:email IS NULL OR LOWER(a.email) LIKE LOWER(CONCAT('%', :email, '%')))
          AND (:ip    IS NULL OR a.ip LIKE CONCAT(:ip, '%'))
        ORDER BY a.timestamp DESC
        """)
    List<AuditLog> search(@Param("type")  AuditLog.Type type,
                           @Param("email") String email,
                           @Param("ip")    String ip,
                           Pageable pageable);

    @Modifying @Transactional
    @Query("DELETE FROM AuditLog a WHERE a.timestamp < :before")
    int deleteOlderThan(@Param("before") LocalDateTime before);
}
