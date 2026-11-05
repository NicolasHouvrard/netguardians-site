package com.logicielpourtous.site.features.registration;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "subscription_requests")
@Getter @Setter @NoArgsConstructor
public class SubscriptionRequest {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", nullable = false)
    private String companyName;

    @Column(name = "contact_name", nullable = false)
    private String contactName;

    @Column(nullable = false)
    private String email;

    private String phone;

    @Enumerated(EnumType.STRING)
    private SubscriptionPlan plan;

    @Enumerated(EnumType.STRING)
    private SubscriptionRequestStatus status = SubscriptionRequestStatus.PENDING;

    @Column(name = "requested_at")
    private LocalDateTime requestedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(columnDefinition = "TEXT")
    private String adminNotes;

    @PrePersist
    protected void onCreate() { this.requestedAt = LocalDateTime.now(); }

    // ── Enums ──────────────────────────────────────────────────────────────────

    public enum SubscriptionPlan {
        DISPATCH("Technicien Dispatch",
                "Accès dispatch d'interventions et gestion des techniciens terrain.",
                "text-bg-secondary"),
        STANDARD("ESN Standard",
                "Gestion complète : tickets, interventions, clients, facturation.",
                "text-bg-primary"),
        GOLD("ESN Gold",
                "Toutes les fonctionnalités Standard + marketplace, API et support prioritaire.",
                "text-bg-warning");

        public final String label;
        public final String description;
        public final String badgeCss;

        SubscriptionPlan(String label, String description, String badgeCss) {
            this.label       = label;
            this.description = description;
            this.badgeCss    = badgeCss;
        }
    }

    public enum SubscriptionRequestStatus {
        PENDING("En attente",  "text-bg-warning"),
        APPROVED("Approuvée", "text-bg-success"),
        REJECTED("Refusée",   "text-bg-danger");

        public final String label;
        public final String badgeCss;

        SubscriptionRequestStatus(String label, String badgeCss) {
            this.label    = label;
            this.badgeCss = badgeCss;
        }
    }
}
