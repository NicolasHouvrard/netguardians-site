package com.logicielpourtous.site.config;

import com.logicielpourtous.site.features.accounting.*;
import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.module.AdminModule;
import com.logicielpourtous.site.features.module.AdminModuleRepository;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.role.RoleRepository;
import com.logicielpourtous.site.features.vatrate.VatRate;
import com.logicielpourtous.site.features.vatrate.VatRateRepository;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

@Configuration
public class DataInitializer {

    @Bean
    CommandLineRunner initDatabase(RoleRepository roleRepository,
                                   AdminModuleRepository moduleRepository,
                                   VatRateRepository vatRateRepository,
                                   JdbcTemplate jdbc) {
        return args -> {
            // ── Nettoyage des anciens modules obsolètes ───────────────────────
            moduleRepository.findFirstByUrl("/admin/pages").ifPresent(moduleRepository::delete);

            // ── Migrations de colonnes ────────────────────────────────────────
            // Boolean boxé → Hibernate crée la colonne nullable sans DEFAULT
            // On backfille les NULL pour les utilisateurs existants
            jdbc.execute("UPDATE public.users SET must_change_password = false WHERE must_change_password IS NULL");
            // Backfill active=true pour les comptes créés avant la colonne (valeur NULL ou false par défaut DB)
            try {
                // Utilisateurs ESN (rôles internes) : toujours actifs, même si email_verification_token est set
                jdbc.execute("""
                    UPDATE public.users u SET active = true
                    WHERE (u.active IS NULL OR u.active = false)
                    AND (
                        u.email_verification_token IS NULL
                        OR EXISTS (
                            SELECT 1 FROM public.users_roles ur
                            JOIN public.roles r ON r.id = ur.role_id
                            WHERE ur.user_id = u.id AND r.type = 'ESN'
                        )
                    )
                """);
            } catch (Exception e) {
                // Fallback si les colonnes/tables n'existent pas encore
                jdbc.execute("UPDATE public.users SET active = true WHERE active IS NULL OR active = false");
            }

            // ── Déduplication admin_modules (évite IncorrectResultSizeDataAccessException) ──
            // 1. Réassigner les dashboard_module_links vers la ligne survivante (MIN id)
            //    avant de supprimer les doublons, pour ne pas violer la FK.
            jdbc.execute("""
                UPDATE dashboard_module_links dml
                SET module_id = survivors.min_id
                FROM admin_modules am
                JOIN (
                    SELECT MIN(id) AS min_id, url
                    FROM admin_modules
                    GROUP BY url
                    HAVING COUNT(*) > 1
                ) survivors ON am.url = survivors.url AND am.id <> survivors.min_id
                WHERE dml.module_id = am.id
            """);
            // 2. Supprimer les doublons (les FK pointent maintenant toutes vers MIN id)
            jdbc.execute("""
                DELETE FROM admin_modules
                WHERE id NOT IN (
                    SELECT MIN(id) FROM admin_modules GROUP BY url
                )
            """);
            // 3. Supprimer la contrainte UNIQUE si elle existe (trop stricte — le module UI peut créer des conflits)
            jdbc.execute("""
                DO $$ BEGIN
                    ALTER TABLE admin_modules DROP CONSTRAINT IF EXISTS uq_admin_modules_url;
                EXCEPTION WHEN OTHERS THEN NULL;
                END $$
            """);

            // Supprime les contraintes CHECK sur les enums étendus (ddl-auto=update ne les met pas à jour)
            jdbc.execute("ALTER TABLE interventions DROP CONSTRAINT IF EXISTS interventions_status_check");
            jdbc.execute("ALTER TABLE interventions DROP CONSTRAINT IF EXISTS interventions_assignment_mode_check");
            jdbc.execute("ALTER TABLE interventions DROP CONSTRAINT IF EXISTS interventions_billing_type_check");
            jdbc.execute("ALTER TABLE interventions DROP CONSTRAINT IF EXISTS interventions_type_check");
            jdbc.execute("ALTER TABLE shop_orders DROP CONSTRAINT IF EXISTS shop_orders_status_check");
            jdbc.execute("ALTER TABLE page_blocks DROP CONSTRAINT IF EXISTS page_blocks_block_type_check");
            jdbc.execute("ALTER TABLE social_network_configs DROP CONSTRAINT IF EXISTS social_network_configs_network_type_check");
            jdbc.execute("ALTER TABLE social_post_targets DROP CONSTRAINT IF EXISTS social_post_targets_status_check");
            // Champs chiffrés AES : passage de varchar(255) à TEXT si la table existe déjà
            jdbc.execute("ALTER TABLE payment_configs ALTER COLUMN iban TYPE TEXT");
            jdbc.execute("ALTER TABLE payment_configs ALTER COLUMN bic TYPE TEXT");
            jdbc.execute("ALTER TABLE payment_configs ALTER COLUMN stripe_secret_key TYPE TEXT");
            jdbc.execute("ALTER TABLE payment_configs ALTER COLUMN stripe_publishable_key TYPE TEXT");
            jdbc.execute("ALTER TABLE payment_configs ALTER COLUMN stripe_webhook_secret TYPE TEXT");
            // ── Taux de TVA ──────────────────────────────────────────────────
            seedVatRate(vatRateRepository, "Taux normal",          new BigDecimal("20.00"), Customer.FiscalZone.FRANCE,     true);
            seedVatRate(vatRateRepository, "Taux intermédiaire",   new BigDecimal("10.00"), Customer.FiscalZone.FRANCE,     false);
            seedVatRate(vatRateRepository, "Taux réduit",          new BigDecimal("5.50"),  Customer.FiscalZone.FRANCE,     false);
            seedVatRate(vatRateRepository, "Taux super-réduit",    new BigDecimal("2.10"),  Customer.FiscalZone.FRANCE,     false);
            seedVatRate(vatRateRepository, "Autoliquidation",      new BigDecimal("0.00"),  Customer.FiscalZone.EU,         true);
            seedVatRate(vatRateRepository, "Exportation",          new BigDecimal("0.00"),  Customer.FiscalZone.OUTSIDE_EU, true);
            // ── Rôles ────────────────────────────────────────────────────────
            seedRole(roleRepository, "ROLE_ADMIN",        "Administrateur",  "ESN");
            seedRole(roleRepository, "ROLE_USER",         "Utilisateur",     "ESN");
            seedRole(roleRepository, "ROLE_TECHNICIEN",   "Technicien",      "ESN");
            seedRole(roleRepository, "ROLE_COMMERCIAL",   "Commercial",      "ESN");
            seedRole(roleRepository, "ROLE_MARKETING",    "Marketing",       "ESN");
            seedRole(roleRepository, "ROLE_COMPTABLE",    "Comptable",       "ESN");
            seedRole(roleRepository, "ROLE_GESTIONNAIRE", "Gestionnaire",    "CLIENT");
            seedRole(roleRepository, "ROLE_CLIENT",       "Client",          "CLIENT");

            // ── Modules ESN ──────────────────────────────────────────────────
            seedModule(moduleRepository, "Dashboard Commercial",
                    "fas fa-chart-line", "#3b82f6",
                    "/intra/commercial/dashboard", "ROLE_USER",
                    "Vue commerciale : clients, tickets, interventions, CA",
                    "Commercial", 5, "ESN", null);

            seedModule(moduleRepository, "Support & Incidents",
                    "fas fa-ticket", "#f59e0b",
                    "/intra/tickets", "ROLE_USER",
                    "Tickets, demandes et incidents clients",
                    "Support", 10, "ESN", null);

            seedModule(moduleRepository, "Parc Informatique",
                    "fas fa-desktop", "#6366f1",
                    "/intra/devices", "ROLE_USER",
                    "Appareils et équipements via NinjaOne",
                    "Infrastructure", 20, "ESN", null);

            seedModule(moduleRepository, "Abonnements & Contrats",
                    "fas fa-box-open", "#10b981",
                    "/intra/subscriptions", "ROLE_USER",
                    "Contrats actifs et abonnements clients",
                    "Infrastructure", 21, "ESN", null);

            seedModule(moduleRepository, "Fiches Clients",
                    "fas fa-building", "#3b82f6",
                    "/intra/customers", "ROLE_USER",
                    "Fiches, contacts, sites et équipes portail",
                    "Clients", 30, "ESN", null);

            seedModule(moduleRepository, "Calendrier",
                    "fas fa-calendar-check", "#0ea5e9",
                    "/intra/appointments", "ROLE_TECHNICIEN",
                    "Planning et prise de rendez-vous",
                    "Technicien", 40, "ESN", null);

            seedModule(moduleRepository, "Interventions",
                    "fas fa-wrench", "#8b5cf6",
                    "/intra/interventions", "ROLE_TECHNICIEN",
                    "Suivi des interventions techniques",
                    "Technicien", 41, "ESN", null);

            seedModule(moduleRepository, "Place de Marché",
                    "fas fa-store", "#06b6d4",
                    "/intra/marketplace", "ROLE_COMMERCIAL",
                    "Interventions publiées ouvertes aux ESN partenaires",
                    "Technicien", 42, "ESN", null);

            seedModule(moduleRepository, "Facturation Interventions",
                    "fas fa-file-invoice-dollar", "#16a34a",
                    "/intra/interventions/billing", "ROLE_COMMERCIAL",
                    "Génération des factures mensuelles ou par intervention",
                    "Technicien", 43, "ESN", null);

            seedModule(moduleRepository, "Messagerie",
                    "fas fa-envelope", "#3b82f6",
                    "/intra/mail/accounts", "ROLE_ADMIN",
                    "Comptes mail IMAP/SMTP pour la réception et l'envoi automatique",
                    "Administration", 86, "ESN", null);

            seedModule(moduleRepository, "Demandes d'inscription",
                    "fas fa-user-clock", "#f59e0b",
                    "/intra/registrations", "ROLE_ADMIN",
                    "Demandes d'abonnement ESN en attente de traitement",
                    "Administration", 87, "ESN", null);

            seedModule(moduleRepository, "ESN Partenaires",
                    "fas fa-handshake", "#7c3aed",
                    "/intra/esns", "ROLE_ADMIN",
                    "Réseau d'ESN sous-traitantes pour la place de marché",
                    "Administration", 88, "ESN", null);

            seedModule(moduleRepository, "Catalogue Produits",
                    "fas fa-box", "#22c55e",
                    "/intra/products", "ROLE_COMMERCIAL",
                    "Produits, services et tarifs",
                    "Commercial", 50, "ESN", null);

            seedModule(moduleRepository, "Commandes",
                    "fas fa-shopping-cart", "#16a34a",
                    "/intra/orders", "ROLE_COMMERCIAL",
                    "Suivi et gestion des commandes clients",
                    "Commercial", 51, "ESN", null);

            seedModule(moduleRepository, "Factures Clients",
                    "fas fa-file-invoice", "#15803d",
                    "/intra/invoices", "ROLE_COMMERCIAL",
                    "Émission et suivi des factures de vente",
                    "Commercial", 52, "ESN", null);

            seedModule(moduleRepository, "Campagnes Email",
                    "fas fa-envelope", "#f97316",
                    "/intra/campaigns", "ROLE_MARKETING",
                    "Création et envoi de campagnes marketing",
                    "Marketing", 60, "ESN", null);

            seedModule(moduleRepository, "Blog",
                    "fas fa-newspaper", "#ea580c",
                    "/intra/blog", "ROLE_MARKETING",
                    "Rédaction et publication d'articles",
                    "Marketing", 61, "ESN", null);

            seedModule(moduleRepository, "Publications Réseaux Sociaux",
                    "fas fa-share-nodes", "#7c3aed",
                    "/intra/social/posts", "ROLE_MARKETING",
                    "Publication multi-réseau : LinkedIn, Facebook, Instagram, X",
                    "Marketing", 62, "ESN", null);

            seedModule(moduleRepository, "Pages du Site",
                    "fas fa-file-alt", "#0284c7",
                    "/intra/pages", "ROLE_MARKETING",
                    "Création et édition des pages publiques avec blocs et widgets",
                    "Site Web", 62, "ESN", null);

            seedModule(moduleRepository, "Menu de Navigation",
                    "fas fa-bars", "#0369a1",
                    "/intra/menu", "ROLE_MARKETING",
                    "Structure et ordre du menu de navigation public",
                    "Site Web", 63, "ESN", null);

            seedModule(moduleRepository, "Médiathèque",
                    "fas fa-images", "#0891b2",
                    "/intra/images", "ROLE_MARKETING",
                    "Images uploadées utilisables dans les pages et widgets",
                    "Site Web", 64, "ESN", null);

            seedModule(moduleRepository, "Paramètres du Site",
                    "fas fa-cog", "#64748b",
                    "/intra/settings/site", "ROLE_ADMIN",
                    "Nom du site, favicon, URL, contact, réseaux sociaux et tracking",
                    "Site Web", 65, "ESN", null);

            seedModule(moduleRepository, "Taux de TVA",
                    "fas fa-percent", "#2563eb",
                    "/intra/vat-rates", "ROLE_COMPTABLE",
                    "Gestion des taux de TVA par zone fiscale",
                    "Comptabilité", 69, "ESN", null);

            seedModule(moduleRepository, "Tableau de Bord Comptable",
                    "fas fa-calculator", "#2563eb",
                    "/intra/accounting", "ROLE_COMPTABLE",
                    "KPIs financiers, CA encaissé, impayés",
                    "Comptabilité", 70, "ESN", null);

            seedModule(moduleRepository, "Toutes les Factures",
                    "fas fa-file-invoice-dollar", "#1d4ed8",
                    "/intra/accounting/invoices", "ROLE_COMPTABLE",
                    "Ventes et achats, suivi des statuts",
                    "Comptabilité", 71, "ESN", null);

            seedModule(moduleRepository, "Fournisseurs",
                    "fas fa-truck", "#64748b",
                    "/intra/suppliers", "ROLE_COMMERCIAL",
                    "Portefeuille fournisseurs et factures d'achat",
                    "Fournisseurs", 80, "ESN", null);

            seedModule(moduleRepository, "Factures fournisseurs",
                    "fas fa-file-import", "#d97706",
                    "/intra/supplier-invoices", "ROLE_COMPTABLE",
                    "Factures reçues, OCR Mindee, comptabilisation automatique",
                    "Fournisseurs", 81, "ESN", null);

            seedModule(moduleRepository, "Équipes",
                    "fas fa-people-group", "#7c3aed",
                    "/intra/teams", "ROLE_ADMIN",
                    "Groupes de techniciens et types de demandes associés",
                    "Administration", 89, "ESN", null);

            seedModule(moduleRepository, "Utilisateurs",
                    "fas fa-user-cog", "#dc2626",
                    "/intra/users", "ROLE_ADMIN",
                    "Gestion des comptes, rôles et accès",
                    "Administration", 90, "ESN", null);

            seedModule(moduleRepository, "Rôles & Permissions",
                    "fas fa-shield-alt", "#b91c1c",
                    "/intra/roles", "ROLE_ADMIN",
                    "Création et gestion des rôles ESN et CLIENT",
                    "Administration", 91, "ESN", null);

            seedModule(moduleRepository, "Gestion de Stock",
                    "fas fa-boxes", "#0891b2",
                    "/intra/stock", "ROLE_TECHNICIEN",
                    "Stock par site : entrées, sorties, transferts et alertes",
                    "Infrastructure", 23, "ESN", null);

            seedModule(moduleRepository, "Config Clients",
                    "fas fa-plug", "#6366f1",
                    "/intra/customers/config", "ROLE_TECHNICIEN",
                    "NinjaOne, Microsoft, Google, Slack et autres paramètres par client",
                    "Infrastructure", 22, "ESN", null);

            seedModule(moduleRepository, "Boutique — Commandes",
                    "fas fa-shop", "#f43f5e",
                    "/intra/shop/orders", "ROLE_COMMERCIAL",
                    "Commandes boutique, vendeurs ESN — publier les produits depuis le Catalogue",
                    "Commercial", 53, "ESN", null);

            seedModule(moduleRepository, "Synchronisation",
                    "fas fa-sync-alt", "#6366f1",
                    "/intra/sync", "ROLE_ADMIN",
                    "Lancer, planifier et suivre les synchronisations de catalogues fournisseurs",
                    "Fournisseurs", 82, "ESN", null);

            seedModule(moduleRepository, "Boutique — Paramètres",
                    "fas fa-store-alt", "#f43f5e",
                    "/intra/shop/settings", "ROLE_ADMIN",
                    "Nom de la boutique, URL de base et liens vers les moyens de paiement",
                    "Commercial", 54, "ESN", null);

            seedModule(moduleRepository, "Prospects CRM",
                    "fas fa-users-line", "#6366f1",
                    "/intra/prospects", "ROLE_COMMERCIAL",
                    "Pipeline prospects : suivi, activités et conversion en client",
                    "Commercial", 55, "ESN", null);

            seedModule(moduleRepository, "Devis CRM",
                    "fas fa-file-signature", "#0ea5e9",
                    "/intra/quotes", "ROLE_COMMERCIAL",
                    "Création et suivi des devis, conversion en commande",
                    "Commercial", 56, "ESN", null);

            seedModule(moduleRepository, "Objectifs Commerciaux",
                    "fas fa-bullseye", "#10b981",
                    "/intra/commercial/objectives", "ROLE_COMMERCIAL",
                    "Suivi des objectifs par commercial ou par équipe",
                    "Commercial", 57, "ESN", null);

            seedModule(moduleRepository, "Virement Bancaire",
                    "fas fa-university", "#16a34a",
                    "/intra/payment/bank-transfer", "ROLE_ADMIN",
                    "Coordonnées bancaires (IBAN/BIC) pour les paiements par virement — chiffré AES-256",
                    "Paiements", 92, "ESN", null);

            seedModule(moduleRepository, "Stripe",
                    "fab fa-stripe", "#635bff",
                    "/intra/payment/stripe", "ROLE_ADMIN",
                    "Clés API Stripe pour le paiement par carte bancaire — chiffré AES-256",
                    "Paiements", 93, "ESN", null);

            seedModule(moduleRepository, "Configurations",
                    "fas fa-plug", "#6366f1",
                    "/espace/config", "ROLE_GESTIONNAIRE",
                    "Gérez vos intégrations et accès tiers",
                    "Gestion", 43, "CLIENT", null);

            // ── Modules CLIENT (visibles aussi par GESTIONNAIRE) ─────────────
            seedModule(moduleRepository, "Mon Profil",
                    "fas fa-user-circle", "#6366f1",
                    "/espace/profile", "ROLE_CLIENT",
                    "Vos informations personnelles et préférences",
                    "Moi", 5, "CLIENT", null);

            seedModule(moduleRepository, "Mes Demandes",
                    "fas fa-headset", "#f59e0b",
                    "/espace/tickets", "ROLE_CLIENT",
                    "Vos demandes de support et incidents",
                    "Support", 10, "CLIENT", null);

            seedModule(moduleRepository, "Mon Matériel",
                    "fas fa-laptop", "#6366f1",
                    "/espace/devices", "ROLE_CLIENT",
                    "Vos appareils et leur état",
                    "Informatique", 20, "CLIENT", null);

            seedModule(moduleRepository, "Mes Abonnements",
                    "fas fa-box-open", "#10b981",
                    "/espace/subscriptions", "ROLE_CLIENT",
                    "Vos contrats et services souscrits",
                    "Services", 30, "CLIENT", null);

            seedModule(moduleRepository, "Mes Comptes",
                    "fas fa-key", "#8b5cf6",
                    "/espace/accounts", "ROLE_CLIENT",
                    "Vos accès et comptes applicatifs",
                    "Moi", 35, "CLIENT", null);

            seedModule(moduleRepository, "Mes Factures",
                    "fas fa-file-invoice", "#2563eb",
                    "/espace/invoices", "ROLE_CLIENT",
                    "Historique et suivi de vos factures",
                    "Finances", 40, "CLIENT", null);

            // ── Modules GESTIONNAIRE ─────────────────────────────────────────
            seedModule(moduleRepository, "Ma Société",
                    "fas fa-building", "#3b82f6",
                    "/espace/company", "ROLE_GESTIONNAIRE",
                    "Informations, contacts et paramètres de votre entreprise",
                    "Ma société", 50, "CLIENT", null);

            seedModule(moduleRepository, "Mes Collaborateurs",
                    "fas fa-users", "#8b5cf6",
                    "/espace/team", "ROLE_GESTIONNAIRE",
                    "Accès portail et gestion des membres de l'équipe",
                    "Ma société", 51, "CLIENT", null);

            seedModule(moduleRepository, "Mon Parc Informatique",
                    "fas fa-desktop", "#6366f1",
                    "/espace/parc", "ROLE_GESTIONNAIRE",
                    "Vue complète du parc informatique de votre société",
                    "Ma société", 52, "CLIENT", null);

            seedModule(moduleRepository, "Mes Sites",
                    "fas fa-map-marker-alt", "#0ea5e9",
                    "/espace/sites", "ROLE_GESTIONNAIRE",
                    "Vos sites et locaux",
                    "Ma société", 53, "CLIENT", null);

            seedModule(moduleRepository, "Les Demandes",
                    "fas fa-ticket", "#f59e0b",
                    "/espace/requests", "ROLE_GESTIONNAIRE",
                    "Toutes les demandes de support de votre société",
                    "Gestion", 60, "CLIENT", null);

            seedModule(moduleRepository, "Les Commandes",
                    "fas fa-shopping-cart", "#16a34a",
                    "/espace/orders", "ROLE_GESTIONNAIRE",
                    "Vos commandes et leur suivi",
                    "Gestion", 61, "CLIENT", null);

            seedModule(moduleRepository, "Les Factures",
                    "fas fa-file-invoice-dollar", "#1d4ed8",
                    "/espace/billing", "ROLE_GESTIONNAIRE",
                    "Toutes les factures de votre société",
                    "Finances", 62, "CLIENT", null);

            // ── Chat (ESN + CLIENT) ───────────────────────────────────────────
            seedModule(moduleRepository, "Chat",
                    "fas fa-comments", "#3b82f6",
                    "/intra/chat", "ROLE_USER",
                    "Messagerie temps réel avec les visiteurs et les clients",
                    "Support", 11, "ESN", null);

            seedModule(moduleRepository, "Chat Support",
                    "fas fa-comments", "#3b82f6",
                    "/espace/chat", "ROLE_CLIENT",
                    "Discutez en direct avec votre conseiller",
                    "Support", 11, "CLIENT", null);

        };
    }

    @Bean
    CommandLineRunner initAccounting(AdminModuleRepository moduleRepository) {
        return args -> {

            // ── Modules comptabilité (le PCG est initialisé par AccountingSetupService par tenant) ─
            seedModule(moduleRepository, "Configuration Comptabilité",
                    "fas fa-cog", "#6366f1",
                    "/intra/accounting/setup", "ROLE_ADMIN",
                    "Initialiser le PCG standard, les journaux et l'exercice courant",
                    "Comptabilité", 71, "ESN", null);
            seedModule(moduleRepository, "Plan Comptable",
                    "fas fa-list-ol", "#2563eb",
                    "/intra/accounting/plan", "ROLE_COMPTABLE",
                    "Comptes du Plan Comptable Général (PCG) — classes 1 à 7",
                    "Comptabilité", 72, "ESN", null);

            seedModule(moduleRepository, "Journaux Comptables",
                    "fas fa-book", "#1d4ed8",
                    "/intra/accounting/journals", "ROLE_COMPTABLE",
                    "Ventes, achats, banque, salaires, OD…",
                    "Comptabilité", 73, "ESN", null);

            seedModule(moduleRepository, "Exercices Comptables",
                    "fas fa-calendar-alt", "#1e40af",
                    "/intra/accounting/fiscal-years", "ROLE_ADMIN",
                    "Exercices fiscaux : ouverture, clôture, exercice courant",
                    "Comptabilité", 74, "ESN", null);

            seedModule(moduleRepository, "Saisie d'Écritures",
                    "fas fa-pen-to-square", "#3b82f6",
                    "/intra/accounting/entries", "ROLE_COMPTABLE",
                    "Saisie et consultation des écritures comptables",
                    "Comptabilité", 75, "ESN", null);

            seedModule(moduleRepository, "Balance des Comptes",
                    "fas fa-scale-balanced", "#0369a1",
                    "/intra/accounting/reports/balance", "ROLE_COMPTABLE",
                    "Balance générale — totaux débit/crédit et soldes par compte",
                    "Comptabilité", 76, "ESN", null);

            seedModule(moduleRepository, "Grand Livre",
                    "fas fa-book-open", "#0284c7",
                    "/intra/accounting/reports/grand-livre", "ROLE_COMPTABLE",
                    "Détail des mouvements par compte avec solde cumulé",
                    "Comptabilité", 77, "ESN", null);
        };
    }

    @Bean
    CommandLineRunner initQonto(AdminModuleRepository moduleRepository) {
        return args -> {
            seedModule(moduleRepository, "Qonto — Import",
                    "fas fa-landmark", "#0E6FFF",
                    "/intra/accounting/qonto", "ROLE_COMPTABLE",
                    "Import automatique des transactions Qonto vers le plan comptable PCG",
                    "Comptabilité", 79, "ESN", null);
        };
    }

    @Bean
    CommandLineRunner initStripe(AdminModuleRepository moduleRepository) {
        return args -> {
            seedModule(moduleRepository, "Stripe — Import",
                    "fab fa-stripe", "#635bff",
                    "/intra/accounting/stripe", "ROLE_COMPTABLE",
                    "Import automatique des paiements, virements et remboursements Stripe vers le plan comptable",
                    "Comptabilité", 78, "ESN", null);
        };
    }

    @Bean
    CommandLineRunner initPayfit(AdminModuleRepository moduleRepository) {
        return args -> {
            seedModule(moduleRepository, "Payfit / Import FEC",
                    "fas fa-file-import", "#7c3aed",
                    "/intra/accounting/payfit", "ROLE_COMPTABLE",
                    "Import FEC (journaux de paie Payfit, Silae, ADP…) et export FEC pour contrôle DGFiP",
                    "Comptabilité", 80, "ESN", null);
        };
    }

    private void seedVatRate(VatRateRepository repo, String name, BigDecimal rate,
                              Customer.FiscalZone zone, boolean isDefault) {
        if (!repo.existsByName(name)) {
            VatRate vr = new VatRate();
            vr.setName(name);
            vr.setRate(rate);
            vr.setZone(zone);
            vr.setDefault(isDefault);
            repo.save(vr);
        }
    }

    private void seedRole(RoleRepository repo, String name, String label, String type) {
        Role r = repo.findByName(name).orElseGet(Role::new);
        r.setName(name);
        r.setLabel(label);
        r.setType(type);
        repo.save(r);
    }

    private void seedModule(AdminModuleRepository repo,
                             String title, String icon, String color,
                             String url, String roleRequired,
                             String description, String category,
                             int displayOrder, String audience,
                             String subscriptionCode) {
        // Lookup par URL (clé métier stable même si le titre change)
        AdminModule m = repo.findFirstByUrl(url).orElseGet(AdminModule::new);
        m.setTitle(title);
        m.setIcon(icon);
        m.setColor(color);
        m.setUrl(url);
        m.setRoleRequired(roleRequired);
        m.setDescription(description);
        m.setCategory(category);
        m.setDisplayOrder(displayOrder);
        m.setAudience(audience);
        m.setSubscriptionCode(subscriptionCode);
        m.setActive(true);
        repo.save(m);
    }
}
