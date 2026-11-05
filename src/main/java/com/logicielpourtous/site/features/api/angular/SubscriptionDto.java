package com.logicielpourtous.site.features.api.angular;

import com.logicielpourtous.site.features.subscription.Subscription;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SubscriptionDto(
        Long       id,
        String     name,
        String     billingModel,
        String     billingModelLabel,
        String     period,
        LocalDate  startDate,
        LocalDate  endDate,
        boolean    active,
        BigDecimal pricePerPeriod,
        Integer    unitCount,
        BigDecimal unitPrice,
        String     unitLabel,
        Integer    includedMinutes,
        String     formattedIncluded,
        String     formattedSpent,
        int        progressPercent
) {
    public static SubscriptionDto from(Subscription s) {
        var bm = s.getEffectiveBillingModel();
        return new SubscriptionDto(
                s.getId(),
                s.getEffectiveName(),
                bm != null ? bm.name()  : null,
                bm != null ? bm.getLabel() : null,
                s.getEffectivePeriod() != null ? s.getEffectivePeriod().name() : null,
                s.getStartDate(),
                s.getEndDate(),
                s.isActive(),
                s.getEffectivePricePerPeriod(),
                s.getUnitCount(),
                s.getEffectiveUnitPrice(),
                s.getEffectiveUnitLabel(),
                s.getEffectiveIncludedMinutes(),
                s.getFormattedIncluded(),
                s.getFormattedSpent(),
                s.getProgressPercent()
        );
    }
}
