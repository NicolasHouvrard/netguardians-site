package com.logicielpourtous.site.features.intra.widget;

import java.math.BigDecimal;

public final class WidgetUtils {

    private WidgetUtils() {}

    public static String formatAmount(BigDecimal amount) {
        if (amount == null) return "—";
        long val = amount.longValue();
        if (val >= 1_000_000) return String.format("%.1f M€", val / 1_000_000.0);
        if (val >= 1_000)     return String.format("%,d k€", val / 1_000).replace(",", " ");
        return val + " €";
    }
}
