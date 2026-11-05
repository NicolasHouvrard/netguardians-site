package com.logicielpourtous.site.features.subscription;

public enum SubscriptionPeriod {

    MONTHLY("Mensuel"),
    QUARTERLY("Trimestriel"),
    YEARLY("Annuel");

    private final String label;

    SubscriptionPeriod(String label) { this.label = label; }

    public String getLabel() { return label; }
}
