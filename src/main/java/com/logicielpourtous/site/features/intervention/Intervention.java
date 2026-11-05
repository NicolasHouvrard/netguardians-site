package com.logicielpourtous.site.features.intervention;

import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.esn.PartnerEsn;
import com.logicielpourtous.site.features.invoice.Invoice;
import com.logicielpourtous.site.features.request.Request;
import com.logicielpourtous.site.features.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "interventions")
@Getter @Setter @NoArgsConstructor
public class Intervention {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String reference;

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    private InterventionType type = InterventionType.ONSITE;

    @Enumerated(EnumType.STRING)
    private InterventionStatus status = InterventionStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "assignment_mode")
    private AssignmentMode assignmentMode = AssignmentMode.INTERNAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_type")
    private BillingType billingType = BillingType.HOURLY;

    // ── Relations ──────────────────────────────────────────────────────────────

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "technician_id")
    private User technician;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "partner_esn_id")
    private PartnerEsn partnerEsn;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "request_id")
    private Request request;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id")
    private Invoice linkedInvoice;

    @OneToMany(mappedBy = "intervention", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<InterventionApplication> applications = new ArrayList<>();

    @OneToMany(mappedBy = "intervention", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<InterventionImage> images = new ArrayList<>();

    // ── Planning ───────────────────────────────────────────────────────────────

    private LocalDate date;

    @Column(name = "scheduled_start")
    private LocalDateTime scheduledStart;

    @Column(name = "scheduled_end")
    private LocalDateTime scheduledEnd;

    // ── Site ───────────────────────────────────────────────────────────────────

    private String address;
    private String city;

    @Column(name = "zip_code", length = 10)
    private String zipCode;

    // ── Durées ─────────────────────────────────────────────────────────────────

    @Column(name = "duration_minutes")
    private Integer durationMinutes = 0;

    @Column(name = "actual_duration_minutes")
    private Integer actualDurationMinutes;

    @Column(name = "actual_start")
    private LocalDateTime actualStart;

    @Column(name = "actual_end")
    private LocalDateTime actualEnd;

    // ── Contenu ────────────────────────────────────────────────────────────────

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String resolution;

    private Boolean successful;

    @Column(name = "technician_signature", columnDefinition = "TEXT")
    private String technicianSignature;

    @Column(name = "technician_signature_name")
    private String technicianSignatureName;

    @Column(name = "client_signature", columnDefinition = "TEXT")
    private String clientSignature;

    @Column(name = "client_signature_name")
    private String clientSignatureName;

    // ── Tarification ───────────────────────────────────────────────────────────

    @Column(name = "hourly_rate_client", precision = 10, scale = 2)
    private BigDecimal hourlyRateClient;

    @Column(name = "fixed_amount_client", precision = 10, scale = 2)
    private BigDecimal fixedAmountClient;

    @Column(name = "hourly_rate_esn", precision = 10, scale = 2)
    private BigDecimal hourlyRateEsn;

    @Column(name = "fixed_amount_esn", precision = 10, scale = 2)
    private BigDecimal fixedAmountEsn;

    // ── Calendrier ────────────────────────────────────────────────────────────

    @Column(name = "calendar_event_id")
    private String calendarEventId;

    @Column(name = "calendar_account_id")
    private Long calendarAccountId;

    // ── Audit ──────────────────────────────────────────────────────────────────

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); }

    // ── Helpers ────────────────────────────────────────────────────────────────

    public String getFormattedDuration() {
        Integer min = actualDurationMinutes != null ? actualDurationMinutes : durationMinutes;
        if (min == null || min == 0) return "0 min";
        int h = min / 60, m = min % 60;
        return h > 0 ? h + "h" + (m > 0 ? String.format("%02d", m) : "") : m + " min";
    }

    /** Montant HT à facturer au client selon la durée réelle et le mode de tarif */
    public BigDecimal getClientAmount() {
        if (billingType == BillingType.FIXED) return fixedAmountClient;
        if (hourlyRateClient == null) return BigDecimal.ZERO;
        int min = actualDurationMinutes != null ? actualDurationMinutes : (durationMinutes != null ? durationMinutes : 0);
        return hourlyRateClient.multiply(BigDecimal.valueOf(min)).divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP);
    }

    /** Montant HT à payer à l'ESN partenaire */
    public BigDecimal getEsnAmount() {
        if (billingType == BillingType.FIXED) return fixedAmountEsn;
        if (hourlyRateEsn == null) return BigDecimal.ZERO;
        int min = actualDurationMinutes != null ? actualDurationMinutes : (durationMinutes != null ? durationMinutes : 0);
        return hourlyRateEsn.multiply(BigDecimal.valueOf(min)).divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP);
    }

    // ── Enums ──────────────────────────────────────────────────────────────────

    public enum InterventionType {
        ONSITE("Sur site"), REMOTE("À distance"), MAINTENANCE("Maintenance");
        public final String label;
        InterventionType(String label) { this.label = label; }
    }

    public enum AssignmentMode {
        INTERNAL("Technicien interne"), MARKETPLACE("Place de marché");
        public final String label;
        AssignmentMode(String label) { this.label = label; }
    }

    public enum BillingType {
        HOURLY("Taux horaire"), FIXED("Forfait"), NONE("Non facturable");
        public final String label;
        BillingType(String label) { this.label = label; }
    }

    public enum InterventionStatus {
        DRAFT("Brouillon", "text-bg-secondary"),
        PUBLISHED("Publiée", "text-bg-info"),
        ASSIGNED("Assignée", "text-bg-primary"),
        IN_PROGRESS("En cours", "text-bg-warning"),
        COMPLETED("Terminée", "text-bg-success"),
        INVOICED("Facturée", "text-bg-success"),
        BILLED("Facturée", "text-bg-success"),
        CANCELLED("Annulée", "text-bg-dark");

        public final String label;
        public final String badgeCss;
        InterventionStatus(String label, String badgeCss) {
            this.label    = label;
            this.badgeCss = badgeCss;
        }
    }
}
