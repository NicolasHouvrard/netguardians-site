package com.logicielpourtous.site.features.request;

public enum RequestPriority {
    LOW("Basse", "text-bg-info"),
    MEDIUM("Moyenne", "text-bg-warning"),
    HIGH("Haute", "text-bg-danger"),
    URGENT("Urgent", "text-bg-dark");

    private final String displayValue;
    private final String colorClass;

    RequestPriority(String displayValue, String colorClass) {
        this.displayValue = displayValue;
        this.colorClass = colorClass;
    }

    public String getDisplayValue() { return displayValue; }
    public String getColorClass() { return colorClass; }
}
