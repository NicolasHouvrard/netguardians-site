package com.logicielpourtous.site.features.subscription;

import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.request.Request;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "subscriptions")
@Getter @Setter
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nom personnalisé (null = utilise plan.name) */
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    /** Formule de référence (catalogue ESN) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id")
    private SubscriptionPlan plan;

    // ── Valeurs négociées (null = utilise la valeur du plan) ───────────────

    @Enumerated(EnumType.STRING)
    private SubscriptionBillingModel billingModel;

    @Enumerated(EnumType.STRING)
    private SubscriptionPeriod period;

    /** FORFAIT : minutes incluses (null = valeur plan) */
    private Integer includedMinutes;

    /** FORFAIT : prix par période en € (null = valeur plan) */
    @Column(precision = 10, scale = 2)
    private BigDecimal pricePerPeriod;

    /** HOURLY : tarif €/h (null = valeur plan) */
    @Column(precision = 10, scale = 2)
    private BigDecimal hourlyRate;

    /** PER_UNIT : nombre d'unités (postes, utilisateurs…) */
    private Integer unitCount;

    /** PER_UNIT : prix unitaire négocié (null = valeur plan) */
    @Column(precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false)
    private LocalDate startDate = LocalDate.now();

    private LocalDate endDate;

    private boolean active = true;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @OneToMany(mappedBy = "subscription", fetch = FetchType.LAZY)
    private List<Request> requests = new ArrayList<>();

    // ── Valeurs effectives (override ?? plan default) ─────────────────────

    public String getEffectiveName() {
        if (name != null && !name.isBlank()) return name;
        return plan != null ? plan.getName() : "—";
    }

    public SubscriptionBillingModel getEffectiveBillingModel() {
        if (billingModel != null) return billingModel;
        return plan != null ? plan.getBillingModel() : SubscriptionBillingModel.FORFAIT;
    }

    public SubscriptionPeriod getEffectivePeriod() {
        if (period != null) return period;
        return plan != null ? plan.getDefaultPeriod() : null;
    }

    public Integer getEffectiveIncludedMinutes() {
        if (includedMinutes != null) return includedMinutes;
        return plan != null ? plan.getDefaultIncludedMinutes() : null;
    }

    public BigDecimal getEffectivePricePerPeriod() {
        if (pricePerPeriod != null) return pricePerPeriod;
        return plan != null ? plan.getDefaultPricePerPeriod() : null;
    }

    public BigDecimal getEffectiveHourlyRate() {
        if (hourlyRate != null) return hourlyRate;
        return plan != null ? plan.getDefaultHourlyRate() : null;
    }

    public BigDecimal getEffectiveUnitPrice() {
        if (unitPrice != null) return unitPrice;
        return plan != null ? plan.getDefaultUnitPrice() : null;
    }

    public String getEffectiveUnitLabel() {
        return plan != null && plan.getUnitLabel() != null ? plan.getUnitLabel() : "unité";
    }

    public SubscriptionCategory getEffectiveCategory() {
        return plan != null ? plan.getCategory() : null;
    }

    // ── Calculs consommation ──────────────────────────────────────────────

    public int getTotalSpentMinutes() {
        return requests.stream()
                .flatMap(r -> r.getTimeEntries().stream())
                .mapToInt(e -> e.getDurationMinutes() != null ? e.getDurationMinutes() : 0)
                .sum();
    }

    public String getFormattedIncluded() {
        return formatMinutes(getEffectiveIncludedMinutes());
    }

    public String getFormattedSpent() {
        return formatMinutes(getTotalSpentMinutes());
    }

    public int getProgressPercent() {
        Integer inc = getEffectiveIncludedMinutes();
        if (inc == null || inc == 0) return 0;
        return (int) Math.min(100, getTotalSpentMinutes() * 100L / inc);
    }

    public boolean isOverBudget() {
        Integer inc = getEffectiveIncludedMinutes();
        return inc != null && getTotalSpentMinutes() > inc;
    }

    /** HOURLY : coût estimé */
    public BigDecimal getEstimatedCost() {
        BigDecimal rate = getEffectiveHourlyRate();
        if (rate == null) return BigDecimal.ZERO;
        BigDecimal hours = BigDecimal.valueOf(getTotalSpentMinutes())
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
        return hours.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    /** PER_UNIT : coût mensuel estimé */
    public BigDecimal getUnitMonthlyCost() {
        BigDecimal uPrice = getEffectiveUnitPrice();
        if (uPrice == null || unitCount == null) return BigDecimal.ZERO;
        return uPrice.multiply(BigDecimal.valueOf(unitCount)).setScale(2, RoundingMode.HALF_UP);
    }

    private static String formatMinutes(Integer minutes) {
        if (minutes == null || minutes == 0) return "0h";
        int h = minutes / 60, m = minutes % 60;
        if (h == 0) return m + "min";
        if (m == 0) return h + "h";
        return h + "h" + String.format("%02d", m);
    }
}
