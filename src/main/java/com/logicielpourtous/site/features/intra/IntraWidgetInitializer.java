package com.logicielpourtous.site.features.intra;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * Seeds intra_widgets with the default widget catalog.
 * Each widget is inserted only if its key is absent (idempotent).
 * New widgets added here will appear automatically on the next startup.
 */
@Component
public class IntraWidgetInitializer implements ApplicationRunner {

    private final IntraWidgetRepository widgetRepo;

    public IntraWidgetInitializer(IntraWidgetRepository widgetRepo) {
        this.widgetRepo = widgetRepo;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Corrections d'URLs appliquées à chaque démarrage
        patch("QONTO_A_RAPPROCHER", "/intra/accounting/qonto", "/intra/accounting/qonto/transactions");
        patch("OD_EN_ATTENTE",      "/intra/accounting/od",    "/intra/accounting/batches");

        // Chaque widget est inséré seulement s'il n'existe pas encore
        s("OPEN_TICKETS",              "Tickets ouverts",           "fas fa-ticket",               "#ef4444", "/intra/tickets",
                "ROLE_ADMIN,ROLE_USER,ROLE_TECHNICIEN,ROLE_COMMERCIAL",       1);
        s("INTERVENTIONS_IN_PROGRESS", "Interventions en cours",    "fas fa-wrench",               "#f97316", "/intra/interventions",
                "ROLE_ADMIN,ROLE_USER,ROLE_TECHNICIEN",                        2);
        s("INTERVENTIONS_A_FACTURER",  "Interventions à facturer",  "fas fa-file-invoice",         "#f97316", "/intra/interventions",
                "ROLE_ADMIN,ROLE_USER,ROLE_TECHNICIEN,ROLE_COMPTABLE",         3);
        s("CUSTOMERS",                 "Clients",                   "fas fa-building",             "#6366f1", "/intra/customers",
                "ROLE_ADMIN,ROLE_USER,ROLE_COMMERCIAL",                        4);
        s("COMMANDES_EN_COURS",        "Commandes en cours",        "fas fa-clipboard-list",       "#0ea5e9", "/intra/orders",
                "ROLE_ADMIN,ROLE_USER,ROLE_COMMERCIAL",                        5);
        s("PENDING_SHOP_ORDERS",       "Commandes à valider",       "fas fa-shopping-bag",         "#f59e0b", "/intra/orders",
                "ROLE_ADMIN,ROLE_USER,ROLE_COMMERCIAL",                        6);
        s("ACTIVE_SUBSCRIPTIONS",      "Abonnements actifs",        "fas fa-box-open",             "#10b981", "/intra/subscriptions",
                "ROLE_ADMIN,ROLE_USER,ROLE_COMMERCIAL,ROLE_COMPTABLE",         7);
        s("CA_ENCAISSE",               "CA encaissé",               "fas fa-euro-sign",            "#8b5cf6", "/intra/accounting",
                "ROLE_ADMIN,ROLE_COMPTABLE",                                   8);
        s("PENDING_INVOICES",          "Factures envoyées",         "fas fa-file-invoice-dollar",  "#3b82f6", "/intra/invoices",
                "ROLE_ADMIN,ROLE_COMPTABLE",                                   9);
        s("FACTURES_EN_RETARD",        "Factures en retard",        "fas fa-exclamation-circle",   "#dc2626", "/intra/invoices",
                "ROLE_ADMIN,ROLE_COMPTABLE",                                  10);
        s("QONTO_A_RAPPROCHER",        "Qonto à rapprocher",        "fas fa-building-columns",     "#14b8a6", "/intra/accounting/qonto/transactions",
                "ROLE_ADMIN,ROLE_COMPTABLE",                                  11);
        s("STRIPE_A_RAPPROCHER",       "Stripe à rapprocher",       "fab fa-stripe-s",             "#7c3aed", "/intra/accounting/stripe",
                "ROLE_ADMIN,ROLE_COMPTABLE",                                  12);
        s("OD_EN_ATTENTE",             "OD en brouillon",           "fas fa-file-pen",             "#64748b", "/intra/accounting/od",
                "ROLE_ADMIN,ROLE_COMPTABLE",                                  13);
        s("CAMPAGNES_ACTIVES",         "Campagnes",                 "fas fa-bullhorn",             "#ec4899", "/intra/campaigns",
                "ROLE_ADMIN,ROLE_MARKETING",                                  14);
        s("FAC_CLI",                   "Factures clients",          "fas fa-file-invoice",         "#3b82f6", "/intra/invoices",
                "ROLE_ADMIN,ROLE_COMPTABLE,ROLE_COMMERCIAL",                  15);
        s("FAC_FOU",                   "Factures fournisseurs",     "fas fa-cart-arrow-down",      "#f59e0b", "/intra/supplier-invoices",
                "ROLE_ADMIN,ROLE_COMPTABLE",                                  16);
        s("FAC_CLI_LIST",             "Dernières factures clients","fas fa-file-invoice",         "#3b82f6", "/intra/invoices",
                "ROLE_ADMIN,ROLE_COMPTABLE,ROLE_COMMERCIAL",                  17);
        s("FAC_FOU_LIST",             "Dernières fact. fournisseurs","fas fa-cart-arrow-down",    "#f59e0b", "/intra/supplier-invoices",
                "ROLE_ADMIN,ROLE_COMPTABLE",                                  18);
        s("COMPTA_CLI",               "Factures clients (détail)",  "fas fa-file-invoice-dollar", "#10b981", "/intra/invoices",
                "ROLE_ADMIN,ROLE_COMPTABLE,ROLE_COMMERCIAL",                  19);
        s("COMPTA_FOU",               "Factures fournisseurs (détail)","fas fa-file-import",      "#f59e0b", "/intra/supplier-invoices",
                "ROLE_ADMIN,ROLE_COMPTABLE",                                  20);
        s("TRESORERIE",               "Comptes de trésorerie",      "fas fa-landmark",             "#14b8a6", "/intra/accounting",
                "ROLE_ADMIN,ROLE_COMPTABLE",                                  21);
        s("RAPPORTS_FISCAUX",         "Rapports fiscaux",           "fas fa-scale-balanced",       "#6366f1", "/intra/accounting/fiscal",
                "ROLE_ADMIN,ROLE_COMPTABLE",                                  22);
        s("ACTIONS_COMPTA",           "Actions rapides compta",     "fas fa-bolt",                 "#0ea5e9", "/intra/accounting",
                "ROLE_ADMIN,ROLE_COMPTABLE,ROLE_COMMERCIAL",                  23);
        s("RECENT_TICKETS",           "Tickets ouverts",            "fas fa-ticket",               "#ef4444", "/intra/tickets",
                "ROLE_ADMIN,ROLE_USER,ROLE_TECHNICIEN,ROLE_COMMERCIAL",       24);
        s("PENDING_ORDERS_LIST",      "Commandes à valider",        "fas fa-shopping-bag",         "#f59e0b", "/intra/orders",
                "ROLE_ADMIN,ROLE_USER,ROLE_COMMERCIAL",                       25);
        s("PROSPECTS_PIPELINE",       "Prospects en cours",         "fas fa-users-line",           "#6366f1", "/intra/prospects",
                "ROLE_ADMIN,ROLE_USER,ROLE_COMMERCIAL",                       26);
        s("QUOTES_EN_ATTENTE",        "Devis en attente",           "fas fa-file-signature",       "#0ea5e9", "/intra/quotes",
                "ROLE_ADMIN,ROLE_USER,ROLE_COMMERCIAL",                       27);
        s("TAUX_CONVERSION",          "Taux de conversion",         "fas fa-arrow-trend-up",       "#10b981", "/intra/quotes",
                "ROLE_ADMIN,ROLE_USER,ROLE_COMMERCIAL",                       28);
        s("PIPELINE_VALUE",           "Valeur pipeline",            "fas fa-funnel-dollar",        "#f59e0b", "/intra/prospects",
                "ROLE_ADMIN,ROLE_USER,ROLE_COMMERCIAL",                       29);
        s("QUOTES_DASHBOARD",         "Devis — vue globale",        "fas fa-file-signature",       "#0ea5e9", "/intra/quotes",
                "ROLE_ADMIN,ROLE_USER,ROLE_COMMERCIAL",                       30);
        s("ORDERS_DASHBOARD",         "Commandes — vue globale",    "fas fa-clipboard-list",       "#6366f1", "/intra/orders",
                "ROLE_ADMIN,ROLE_USER,ROLE_COMMERCIAL",                       31);
    }

    private void s(String key, String label, String icon, String color,
                   String link, String roles, int order) {
        if (!widgetRepo.existsByWidgetKey(key)) {
            widgetRepo.save(new IntraWidget(key, label, icon, color, link, roles, order));
        }
    }

    private void patch(String key, String oldLink, String newLink) {
        widgetRepo.findByWidgetKey(key).ifPresent(w -> {
            if (oldLink.equals(w.getLink())) {
                w.setLink(newLink);
                widgetRepo.save(w);
            }
        });
    }
}
