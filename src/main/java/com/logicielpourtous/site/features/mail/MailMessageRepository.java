package com.logicielpourtous.site.features.mail;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MailMessageRepository extends JpaRepository<MailMessage, Long> {
    Optional<MailMessage> findByMessageId(String messageId);
    boolean existsByMessageId(String messageId);
    List<MailMessage> findByRequestIdOrderByProcessedAtAsc(Long requestId);
    void deleteByRequestId(Long requestId);

    @Query("SELECT m FROM MailMessage m WHERE m.request.id = :requestId ORDER BY m.processedAt DESC")
    List<MailMessage> findLastByRequestId(@Param("requestId") Long requestId);
}
