package com.logicielpourtous.site.features.appointment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {
    Page<Appointment> findByStatus(Appointment.AppointmentStatus status, Pageable pageable);
    List<Appointment> findByCustomerIdOrderByStartAtDesc(Long customerId);
    List<Appointment> findByStartAtBetweenOrderByStartAt(LocalDateTime from, LocalDateTime to);
    List<Appointment> findByAssignedToIdOrderByStartAtDesc(Long userId);
    long countByStatus(Appointment.AppointmentStatus status);

    java.util.Optional<Appointment> findByCancellationToken(String cancellationToken);

    @Query("SELECT a.calendarEventId FROM Appointment a WHERE a.calendarEventId IS NOT NULL")
    List<String> findAllCalendarEventIds();

    @Query("""
      SELECT COUNT(a) > 0 FROM Appointment a
      WHERE a.assignedTo.id = :userId
        AND a.status <> com.logicielpourtous.site.features.appointment.Appointment.AppointmentStatus.CANCELLED
        AND a.startAt < :slotEnd
        AND (a.endAt > :slotStart OR (a.endAt IS NULL AND a.startAt >= :slotStart))
      """)
    boolean hasConflict(@Param("userId") Long userId,
                        @Param("slotStart") LocalDateTime slotStart,
                        @Param("slotEnd") LocalDateTime slotEnd);
}
