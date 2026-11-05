package com.logicielpourtous.site.features.intervention;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface InterventionRepository extends JpaRepository<Intervention, Long> {

    List<Intervention> findByCustomerIdOrderByDateDesc(Long customerId);
    List<Intervention> findByTechnicianIdOrderByDateDesc(Long technicianId);
    List<Intervention> findByStatusOrderByDateDesc(Intervention.InterventionStatus status);
    List<Intervention> findByAssignmentModeAndStatusOrderByCreatedAtDesc(
            Intervention.AssignmentMode mode, Intervention.InterventionStatus status);

    long countByStatus(Intervention.InterventionStatus status);
    long countByAssignmentMode(Intervention.AssignmentMode mode);

    /** Interventions terminées non encore facturées, pour la page de facturation */
    List<Intervention> findByStatusAndLinkedInvoiceIsNullOrderByDateAsc(Intervention.InterventionStatus status);

    /** Interventions terminées pour un client, non facturées */
    List<Intervention> findByCustomerIdAndStatusAndLinkedInvoiceIsNullOrderByDateAsc(
            Long customerId, Intervention.InterventionStatus status);

    @Query("SELECT i FROM Intervention i WHERE i.status = 'PUBLISHED' ORDER BY i.createdAt DESC")
    List<Intervention> findPublished();

    @Query("""
        SELECT i FROM Intervention i
        WHERE i.status = 'COMPLETED' AND i.linkedInvoice IS NULL
          AND (:customerId IS NULL OR i.customer.id = :customerId)
          AND (:from IS NULL OR i.date >= :from)
          AND (:to IS NULL OR i.date <= :to)
        ORDER BY i.date ASC
    """)
    List<Intervention> findBillable(
            @Param("customerId") Long customerId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    List<Intervention> findAllByOrderByCreatedAtDesc();

    List<Intervention> findByRequestIdOrderByCreatedAtDesc(Long requestId);

    @Query("SELECT i FROM Intervention i WHERE i.scheduledStart IS NOT NULL AND i.scheduledStart BETWEEN :from AND :to ORDER BY i.scheduledStart")
    List<Intervention> findScheduledBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT i.calendarEventId FROM Intervention i WHERE i.calendarEventId IS NOT NULL")
    List<String> findAllCalendarEventIds();

    @Query("""
      SELECT COUNT(i) > 0 FROM Intervention i
      WHERE i.technician.id = :userId
        AND i.scheduledStart IS NOT NULL
        AND i.scheduledStart < :slotEnd
        AND (i.scheduledEnd > :slotStart OR (i.scheduledEnd IS NULL AND i.scheduledStart >= :slotStart))
      """)
    boolean hasConflict(@Param("userId") Long userId,
                        @Param("slotStart") LocalDateTime slotStart,
                        @Param("slotEnd") LocalDateTime slotEnd);
}
