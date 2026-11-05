package com.logicielpourtous.site.features.crm.activity;

import com.logicielpourtous.site.features.crm.opportunity.CrmOpportunity;
import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "prospect_activities")
@Getter @Setter @NoArgsConstructor
public class ProspectActivity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "opportunity_id")
    private CrmOpportunity opportunity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "commercial_id")
    private User commercial;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ActivityType type;

    @Column(name = "activity_date", nullable = false)
    private LocalDate activityDate;

    private Integer duration;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "next_action")
    private String nextAction;

    @Column(name = "next_action_date")
    private LocalDate nextActionDate;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); }
}
