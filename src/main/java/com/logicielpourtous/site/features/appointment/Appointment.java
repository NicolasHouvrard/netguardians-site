package com.logicielpourtous.site.features.appointment;

import com.logicielpourtous.site.features.customers.Contact;
import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.request.Request;
import com.logicielpourtous.site.features.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "appointments")
@Getter @Setter @NoArgsConstructor
public class Appointment {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "end_at")
    private LocalDateTime endAt;

    @Enumerated(EnumType.STRING)
    private AppointmentType type = AppointmentType.ONSITE;

    @Enumerated(EnumType.STRING)
    private AppointmentStatus status = AppointmentStatus.SCHEDULED;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contact_id")
    private Contact contact;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to_id")
    private User assignedTo;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "calendar_event_id")
    private String calendarEventId;

    @Column(name = "calendar_account_id")
    private Long calendarAccountId;

    @Column(name = "meet_link")
    private String meetLink;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "request_id")
    private Request request;

    @Column(name = "client_email")
    private String clientEmail;

    @Column(name = "cancellation_token")
    private String cancellationToken;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); }

    public enum AppointmentType {
        ONSITE("Sur site"), REMOTE("À distance"), PHONE("Téléphonique"), PRIVATE("Privé");
        public final String label;
        AppointmentType(String label) { this.label = label; }
    }

    public enum AppointmentStatus {
        SCHEDULED("Planifié"), CONFIRMED("Confirmé"), COMPLETED("Terminé"), CANCELLED("Annulé");
        public final String label;
        AppointmentStatus(String label) { this.label = label; }
    }
}
