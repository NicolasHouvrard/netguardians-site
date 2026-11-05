package com.logicielpourtous.site.features.intervention;

import com.logicielpourtous.site.features.esn.PartnerEsn;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "intervention_applications")
@Getter @Setter @NoArgsConstructor
public class InterventionApplication {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "intervention_id", nullable = false)
    private Intervention intervention;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "partner_esn_id", nullable = false)
    private PartnerEsn partnerEsn;

    @Column(name = "proposed_rate", precision = 10, scale = 2)
    private BigDecimal proposedRate;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Enumerated(EnumType.STRING)
    private ApplicationStatus status = ApplicationStatus.PENDING;

    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    @PrePersist
    protected void onCreate() { this.appliedAt = LocalDateTime.now(); }

    public enum ApplicationStatus {
        PENDING("En attente"), ACCEPTED("Acceptée"), REJECTED("Refusée");
        public final String label;
        ApplicationStatus(String label) { this.label = label; }
    }
}
