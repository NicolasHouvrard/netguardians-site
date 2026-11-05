package com.logicielpourtous.site.features.request;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TicketAttachmentRepository extends JpaRepository<TicketAttachment, Long> {
    List<TicketAttachment> findByRequestIdOrderByUploadedAtDesc(Long requestId);
    void deleteByRequestId(Long requestId);
}
