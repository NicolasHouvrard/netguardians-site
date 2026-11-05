package com.logicielpourtous.site.features.subscription;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "subscription_plans")
@Getter @Setter
public class SubscriptionPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private SubscriptionCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubscriptionBillingModel billingModel = SubscriptionBillingModel.FORFAIT;

    @Enumerated(EnumType.STRING)
    private SubscriptionPeriod defaultPeriod;

    /** FORFAIT : minutes incluses par période */
    private Integer defaultIncludedMinutes;

    /** FORFAIT : prix par période (€) */
    @Column(precision = 10, scale = 2)
    private BigDecimal defaultPricePerPeriod;

    /** HOURLY : tarif horaire (€/h) */
    @Column(precision = 10, scale = 2)
    private BigDecimal defaultHourlyRate;

    /** PER_UNIT / PER_USER / PER_DEVICE : prix par unité (€) */
    @Column(precision = 10, scale = 2)
    private BigDecimal defaultUnitPrice;

    /** PER_UNIT : libellé de l'unité (ex : "poste", "domaine") — non utilisé pour PER_USER/PER_DEVICE */
    private String unitLabel;

    private boolean active = true;

    /** Visible pour les clients en auto-inscription */
    private boolean publicPlan = true;

    /** Ce plan provisionne une organisation NinjaOne pour le client */
    @Column(name = "ninja_one_integration", columnDefinition = "boolean not null default false")
    private boolean ninjaOneIntegration = false;

    @Column(name = "sort_order")
    private int sortOrder = 0;

    // ── Helpers affichage ──────────────────────────────────────────────────

    public String getFormattedDefaultIncluded() {
        return formatMinutes(defaultIncludedMinutes);
    }

    private static String formatMinutes(Integer m) {
        if (m == null || m == 0) return "—";
        int h = m / 60, min = m % 60;
        if (h == 0) return min + "min";
        if (min == 0) return h + "h";
        return h + "h" + String.format("%02d", min);
    }
}
