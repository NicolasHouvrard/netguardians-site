package com.logicielpourtous.site.features.intra;

import java.util.List;
import java.util.Set;

public enum IntraWidgetType {

    // ── Tickets / Support ─────────────────────────────────────────────────────
    OPEN_TICKETS(
            "Tickets ouverts",          "fas fa-ticket",                "#ef4444", "/intra/tickets",
            List.of("ROLE_ADMIN","ROLE_USER","ROLE_TECHNICIEN","ROLE_COMMERCIAL")),

    INTERVENTIONS_IN_PROGRESS(
            "Interventions en cours",   "fas fa-wrench",                "#f97316", "/intra/interventions",
            List.of("ROLE_ADMIN","ROLE_USER","ROLE_TECHNICIEN")),

    // ── Commercial ────────────────────────────────────────────────────────────
    CUSTOMERS(
            "Clients",                  "fas fa-building",              "#6366f1", "/intra/customers",
            List.of("ROLE_ADMIN","ROLE_USER","ROLE_COMMERCIAL")),

    PENDING_SHOP_ORDERS(
            "Commandes à valider",      "fas fa-shopping-bag",          "#f59e0b", "/intra/orders",
            List.of("ROLE_ADMIN","ROLE_USER","ROLE_COMMERCIAL")),

    ACTIVE_SUBSCRIPTIONS(
            "Abonnements actifs",       "fas fa-box-open",              "#10b981", "/intra/subscriptions",
            List.of("ROLE_ADMIN","ROLE_USER","ROLE_COMMERCIAL","ROLE_COMPTABLE")),

    // ── Comptabilité ──────────────────────────────────────────────────────────
    CA_ENCAISSE(
            "CA encaissé",              "fas fa-euro-sign",             "#8b5cf6", "/intra/accounting",
            List.of("ROLE_ADMIN","ROLE_COMPTABLE")),

    PENDING_INVOICES(
            "Factures envoyées",        "fas fa-file-invoice-dollar",   "#3b82f6", "/intra/invoices",
            List.of("ROLE_ADMIN","ROLE_COMPTABLE")),

    FACTURES_EN_RETARD(
            "Factures en retard",       "fas fa-exclamation-circle",    "#dc2626", "/intra/invoices",
            List.of("ROLE_ADMIN","ROLE_COMPTABLE")),

    // ── Marketing ─────────────────────────────────────────────────────────────
    CAMPAGNES_ACTIVES(
            "Campagnes",                "fas fa-bullhorn",              "#ec4899", "/intra/campaigns",
            List.of("ROLE_ADMIN","ROLE_MARKETING")),

    // ── Commandes internes ────────────────────────────────────────────────────
    COMMANDES_EN_COURS(
            "Commandes en cours",       "fas fa-clipboard-list",        "#0ea5e9", "/intra/orders",
            List.of("ROLE_ADMIN","ROLE_USER","ROLE_COMMERCIAL")),

    // ── Interventions ─────────────────────────────────────────────────────────
    INTERVENTIONS_A_FACTURER(
            "Interventions à facturer", "fas fa-file-invoice",          "#f97316", "/intra/interventions",
            List.of("ROLE_ADMIN","ROLE_USER","ROLE_TECHNICIEN","ROLE_COMPTABLE")),

    // ── Qonto ─────────────────────────────────────────────────────────────────
    QONTO_A_RAPPROCHER(
            "Qonto à rapprocher",       "fas fa-building-columns",      "#14b8a6", "/intra/accounting/qonto",
            List.of("ROLE_ADMIN","ROLE_COMPTABLE")),

    // ── Stripe ────────────────────────────────────────────────────────────────
    STRIPE_A_RAPPROCHER(
            "Stripe à rapprocher",      "fab fa-stripe-s",              "#7c3aed", "/intra/accounting/stripe",
            List.of("ROLE_ADMIN","ROLE_COMPTABLE")),

    // ── OD / Ecritures ────────────────────────────────────────────────────────
    OD_EN_ATTENTE(
            "OD en brouillon",          "fas fa-file-pen",              "#64748b", "/intra/accounting/od",
            List.of("ROLE_ADMIN","ROLE_COMPTABLE"));

    public final String label;
    public final String icon;
    public final String color;
    public final String link;
    public final List<String> allowedRoles;

    IntraWidgetType(String label, String icon, String color, String link,
                    List<String> allowedRoles) {
        this.label        = label;
        this.icon         = icon;
        this.color        = color;
        this.link         = link;
        this.allowedRoles = allowedRoles;
    }

    public boolean isAllowedFor(Set<String> roles) {
        return allowedRoles.stream().anyMatch(roles::contains);
    }
}
