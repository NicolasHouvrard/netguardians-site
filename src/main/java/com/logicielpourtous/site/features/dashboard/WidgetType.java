package com.logicielpourtous.site.features.dashboard;

import java.util.List;

public enum WidgetType {

    // ── CLIENT + GESTIONNAIRE ─────────────────────────────────────────────
    MY_OPEN_TICKETS(
            "Mes tickets ouverts", "fas fa-headset", "#f59e0b",
            "/espace/tickets",
            List.of("ROLE_CLIENT", "ROLE_GESTIONNAIRE")),

    MY_DEVICES(
            "Mes appareils", "fas fa-laptop", "#6366f1",
            "/espace/devices",
            List.of("ROLE_CLIENT", "ROLE_GESTIONNAIRE")),

    MY_SUBSCRIPTIONS(
            "Mes abonnements actifs", "fas fa-box-open", "#10b981",
            "/espace/subscriptions",
            List.of("ROLE_CLIENT", "ROLE_GESTIONNAIRE")),

    // ── GESTIONNAIRE seulement ────────────────────────────────────────────
    COMPANY_TICKETS(
            "Tickets de la société", "fas fa-ticket", "#ef4444",
            "/espace/requests",
            List.of("ROLE_GESTIONNAIRE")),

    TEAM_COUNT(
            "Collaborateurs", "fas fa-users", "#8b5cf6",
            "/espace/team",
            List.of("ROLE_GESTIONNAIRE")),

    COMPANY_DEVICES(
            "Parc informatique", "fas fa-desktop", "#0ea5e9",
            "/espace/parc",
            List.of("ROLE_GESTIONNAIRE")),

    PENDING_INVOICES(
            "Factures en attente", "fas fa-file-invoice-dollar", "#dc2626",
            "/espace/billing",
            List.of("ROLE_GESTIONNAIRE")),

    COMPANY_SITES(
            "Sites", "fas fa-map-marker-alt", "#64748b",
            "/espace/sites",
            List.of("ROLE_GESTIONNAIRE"));

    public final String label;
    public final String icon;
    public final String color;
    public final String link;
    public final List<String> allowedRoles;

    WidgetType(String label, String icon, String color, String link, List<String> allowedRoles) {
        this.label        = label;
        this.icon         = icon;
        this.color        = color;
        this.link         = link;
        this.allowedRoles = allowedRoles;
    }

    public boolean isAllowedFor(java.util.Set<String> roles) {
        return allowedRoles.stream().anyMatch(roles::contains);
    }
}
