package com.logicielpourtous.site.features.booking;

import com.logicielpourtous.site.features.appointment.Appointment;
import com.logicielpourtous.site.features.team.Team;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Set;

@Entity
@Table(name = "booking_configs")
@Getter @Setter @NoArgsConstructor
public class BookingConfig {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String slug;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    @Enumerated(EnumType.STRING)
    @Column(name = "appointment_type")
    private Appointment.AppointmentType appointmentType = Appointment.AppointmentType.ONSITE;

    @Column(name = "duration_minutes")
    private int durationMinutes = 30;

    @Column(name = "buffer_minutes")
    private int bufferMinutes = 0;

    @ElementCollection
    @CollectionTable(name = "booking_config_open_days",
            joinColumns = @JoinColumn(name = "booking_config_id"))
    @Column(name = "day_of_week")
    @Enumerated(EnumType.STRING)
    private Set<DayOfWeek> openDays = EnumSet.of(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);

    @Column(name = "open_from")
    private LocalTime openFrom = LocalTime.of(9, 0);

    @Column(name = "open_to")
    private LocalTime openTo = LocalTime.of(18, 0);

    @Column(name = "advance_days_max")
    private int advanceDaysMax = 30;

    @Column(name = "with_meet")
    private boolean withMeet = false;

    private boolean active = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
