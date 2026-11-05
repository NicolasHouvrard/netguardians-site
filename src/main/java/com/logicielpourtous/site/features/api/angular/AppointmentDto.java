package com.logicielpourtous.site.features.api.angular;

import com.logicielpourtous.site.features.appointment.Appointment;

import java.time.LocalDateTime;

public record AppointmentDto(
        Long          id,
        String        title,
        LocalDateTime startAt,
        LocalDateTime endAt,
        String        type,
        String        typeLabel,
        String        status,
        String        statusLabel,
        Long          customerId,
        String        customerName,
        Long          assignedToId,
        String        assignedToName,
        String        notes,
        String        meetLink,
        boolean       synced
) {
    public static AppointmentDto from(Appointment a) {
        return new AppointmentDto(
                a.getId(),
                a.getTitle(),
                a.getStartAt(),
                a.getEndAt(),
                a.getType().name(),
                a.getType().label,
                a.getStatus().name(),
                a.getStatus().label,
                a.getCustomer()    != null ? a.getCustomer().getId()           : null,
                a.getCustomer()    != null ? a.getCustomer().getCompanyName()  : null,
                a.getAssignedTo()  != null ? a.getAssignedTo().getId()         : null,
                a.getAssignedTo()  != null ? a.getAssignedTo().getFullName()   : null,
                a.getNotes(),
                a.getMeetLink(),
                a.getCalendarEventId() != null
        );
    }
}
