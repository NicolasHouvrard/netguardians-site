package com.logicielpourtous.site.features.crm.objective;

import com.logicielpourtous.site.features.team.Team;
import com.logicielpourtous.site.features.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "commercial_objectives")
@Getter @Setter @NoArgsConstructor
public class CommercialObjective {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "commercial_id")
    private User commercial;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ObjectivePeriod period;

    @Column(nullable = false)
    private int year;

    private int month;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ObjectiveMetric metric;

    @Column(name = "target_value", precision = 12, scale = 2, nullable = false)
    private BigDecimal targetValue;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); }
}
