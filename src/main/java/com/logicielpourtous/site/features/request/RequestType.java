package com.logicielpourtous.site.features.request;

public enum RequestType {
    SUPPORT("Assistance / SAV", "fa-headset", "text-danger"),
    QUOTATION("Demande de Devis", "fa-file-invoice-dollar", "text-primary"),
    INTERVENTION("Intervention Site", "fa-tools", "text-warning"),
    APPOINTMENT("Rendez-vous", "fa-calendar-check", "text-success"),
    BILLING("Question Facturation", "fa-receipt", "text-info");

    private final String label;
    private final String icon;
    private final String color;

    RequestType(String label, String icon, String color) {
        this.label = label;
        this.icon = icon;
        this.color = color;
    }
    // Getters...
    public String getLabel() { return label; }
    public String getIcon() { return icon; }
    public String getColor() { return color; }
}
