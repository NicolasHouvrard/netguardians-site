package com.logicielpourtous.site.features.customers;

public enum ProspectStatus {
    NOUVEAU      ("Nouveau",       "text-bg-secondary", "#64748b"),
    CONTACTE     ("Contacté",      "text-bg-info",      "#0ea5e9"),
    QUALIFICATION("Qualification", "text-bg-primary",   "#6366f1"),
    PROPOSITION  ("Proposition",   "text-bg-warning",   "#f59e0b"),
    NEGOCIATION  ("Négociation",   "text-bg-warning",   "#f97316"),
    GAGNE        ("Gagné",         "text-bg-success",   "#10b981"),
    PERDU        ("Perdu",         "text-bg-danger",    "#ef4444");

    public final String label;
    public final String badgeCss;
    public final String color;

    ProspectStatus(String label, String badgeCss, String color) {
        this.label    = label;
        this.badgeCss = badgeCss;
        this.color    = color;
    }
}
