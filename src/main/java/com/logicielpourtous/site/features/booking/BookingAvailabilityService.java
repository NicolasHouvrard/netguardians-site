package com.logicielpourtous.site.features.booking;

import com.logicielpourtous.site.features.appointment.AppointmentRepository;
import com.logicielpourtous.site.features.intervention.InterventionRepository;
import com.logicielpourtous.site.features.team.Team;
import com.logicielpourtous.site.features.team.TeamRepository;
import com.logicielpourtous.site.features.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
public class BookingAvailabilityService {

    private final AppointmentRepository appointmentRepository;
    private final InterventionRepository interventionRepository;
    private final TeamRepository teamRepository;

    public BookingAvailabilityService(AppointmentRepository appointmentRepository,
                                      InterventionRepository interventionRepository,
                                      TeamRepository teamRepository) {
        this.appointmentRepository = appointmentRepository;
        this.interventionRepository = interventionRepository;
        this.teamRepository = teamRepository;
    }

    @Transactional(readOnly = true)
    public List<LocalTime> getAvailableSlots(BookingConfig config, LocalDate date) {
        if (!config.getOpenDays().contains(date.getDayOfWeek())) {
            return List.of();
        }
        if (date.isBefore(LocalDate.now())) {
            return List.of();
        }

        if (config.getTeam() == null) return List.of();
        Team team = teamRepository.findByIdWithMembers(config.getTeam().getId()).orElse(null);
        if (team == null || team.getMembers().isEmpty()) {
            return List.of();
        }

        List<User> members = new ArrayList<>(team.getMembers());
        List<LocalTime> available = new ArrayList<>();
        LocalTime cursor = config.getOpenFrom() != null ? config.getOpenFrom() : LocalTime.of(9, 0);
        LocalTime limit  = config.getOpenTo()   != null ? config.getOpenTo()   : LocalTime.of(18, 0);
        int slot = config.getDurationMinutes();
        int buffer = config.getBufferMinutes();
        LocalTime now = LocalTime.now();

        while (!cursor.plusMinutes(slot).isAfter(limit)) {
            if (date.isEqual(LocalDate.now()) && cursor.isBefore(now)) {
                cursor = cursor.plusMinutes(slot + buffer);
                continue;
            }
            LocalDateTime slotStart = LocalDateTime.of(date, cursor);
            LocalDateTime slotEnd = slotStart.plusMinutes(slot);
            boolean anyFree = members.stream().anyMatch(m -> isMemberFree(m, slotStart, slotEnd));
            if (anyFree) {
                available.add(cursor);
            }
            cursor = cursor.plusMinutes(slot + buffer);
        }
        return available;
    }

    @Transactional(readOnly = true)
    public Optional<User> findAvailableMember(BookingConfig config, LocalDate date, LocalTime time) {
        if (config.getTeam() == null) return Optional.empty();
        Team team = teamRepository.findByIdWithMembers(config.getTeam().getId()).orElse(null);
        if (team == null || team.getMembers().isEmpty()) {
            return Optional.empty();
        }
        List<User> members = new ArrayList<>(team.getMembers());
        Collections.shuffle(members);
        LocalDateTime slotStart = LocalDateTime.of(date, time);
        LocalDateTime slotEnd = slotStart.plusMinutes(config.getDurationMinutes());
        return members.stream().filter(m -> isMemberFree(m, slotStart, slotEnd)).findFirst();
    }

    private boolean isMemberFree(User member, LocalDateTime slotStart, LocalDateTime slotEnd) {
        return !appointmentRepository.hasConflict(member.getId(), slotStart, slotEnd)
                && !interventionRepository.hasConflict(member.getId(), slotStart, slotEnd);
    }
}
