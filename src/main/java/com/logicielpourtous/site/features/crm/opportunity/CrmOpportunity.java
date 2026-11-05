package com.logicielpourtous.site.features.crm.opportunity;

import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.customers.ProspectSource;
import com.logicielpourtous.site.features.customers.ProspectStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "crm_opportunities")
@Getter @Setter @NoArgsConstructor
public class CrmOpportunity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Enumerated(EnumType.STRING)
    private ProspectStatus status = ProspectStatus.NOUVEAU;

    @Enumerated(EnumType.STRING)
    private ProspectSource source;

    @Column(name = "assigned_commercial_id")
    private Long assignedCommercialId;

    @Column(name = "assigned_team_id")
    private Long assignedTeamId;

    @Column(precision = 12, scale = 2)
    private BigDecimal estimatedValue;

    @Column(name = "expected_close_date")
    private LocalDate expectedCloseDate;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "lost_reason")
    private String lostReason;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); }
}
