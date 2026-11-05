-- ============================================================
-- SCRIPT DE RÉINITIALISATION DES DONNÉES MÉTIER
-- ============================================================
-- Ce script supprime toutes les données métier tout en
-- conservant la configuration du site public et les modules.
--
-- CONSERVÉ :
--   admin_modules     → modules de navigation intra
--   public_pages      → pages du site public
--   page_blocks       → blocs de contenu des pages
--   menu_items        → menus de navigation public
--   site_settings     → paramètres du site
--   tenants           → tenants (multi-entreprise)
--   roles             → rôles utilisateurs
--   users / users_roles → comptes utilisateurs intra
--   vat_rates         → taux de TVA
--
-- SUPPRIMÉ : toutes les données métier (clients, commandes,
--   factures, produits, tickets, comptabilité, etc.)
--
-- USAGE :
--   psql -h HOST -U USER -d DATABASE -f reset-prod-data.sql
--
-- ⚠ IRRÉVERSIBLE — faire un dump avant d'exécuter :
--   pg_dump -h HOST -U USER DATABASE > backup-$(date +%Y%m%d-%H%M%S).sql
-- ============================================================

BEGIN;

-- PostgreSQL : une seule instruction TRUNCATE sur plusieurs tables
-- gère automatiquement l'ordre des FK.

TRUNCATE TABLE

    -- == Synchronisation & connecteurs ==
    sync_logs,
    platform_connectors,

    -- == Comptabilité ==
    accounting_entry_lines,
    accounting_entries,
    accounting_batches,
    accounting_journals,
    accounting_accounts,
    fiscal_years,
    qonto_accounting_mappings,
    stripe_accounting_mappings,
    qonto_transactions,
    qonto_config,
    stripe_transactions,

    -- == Achats fournisseurs ==
    supplier_invoices,
    supplier_products,

    -- == Stock ==
    stock_movements,
    stock_items,

    -- == Boutique en ligne ==
    shop_order_lines,
    shop_orders,
    shop_products,
    shop_vendors,
    shop_configs,

    -- == Commandes & Facturation ==
    invoice_lines,
    invoices,
    order_lines,
    orders,

    -- == Tickets / Support ==
    ticket_attachments,
    time_entries,
    comments,
    requests,

    -- == Interventions ==
    intervention_images,
    intervention_applications,
    interventions,

    -- == Abonnements ==
    subscriptions,
    subscription_plans,
    subscription_categories,
    subscription_requests,

    -- == Produits ==
    products,
    product_categories,

    -- == Clients, contacts & sites ==
    sites,
    contacts,
    customers,

    -- == Marketing ==
    mail_campaigns,
    appointments,
    blog_posts,

    -- == Messagerie ==
    mail_messages,
    mail_accounts,

    -- == Intégrations externes ==
    ninja_one_config,
    payment_configs,
    client_integrations,
    partner_esns,

    -- == Dashboard intra (recréé automatiquement au démarrage) ==
    dashboard_widget_layouts,
    user_dashboard_configs,
    dashboard_module_links,
    intra_dashboards,
    intra_widgets,

    -- == Équipes ==
    team_members,
    teams

RESTART IDENTITY;

-- == Utilisateurs portail client ==
-- Supprime les comptes ayant uniquement le rôle ROLE_CLIENT
-- (les comptes intra ROLE_ADMIN / ROLE_COMMERCIAL / ROLE_TECH sont conservés)
DELETE FROM users_roles
WHERE user_id IN (
    SELECT u.id FROM users u
    WHERE NOT EXISTS (
        SELECT 1 FROM users_roles ur
        JOIN roles r ON r.id = ur.role_id
        WHERE ur.user_id = u.id
          AND r.name IN ('ROLE_ADMIN', 'ROLE_COMMERCIAL', 'ROLE_TECH',
                         'ROLE_MANAGER', 'ROLE_ACCOUNTANT', 'ROLE_USER')
    )
);

DELETE FROM users
WHERE NOT EXISTS (
    SELECT 1 FROM users_roles ur WHERE ur.user_id = users.id
);

COMMIT;

-- Vérification rapide après exécution
SELECT 'customers'          AS table_name, COUNT(*) AS lignes FROM customers
UNION ALL
SELECT 'orders',            COUNT(*) FROM orders
UNION ALL
SELECT 'invoices',          COUNT(*) FROM invoices
UNION ALL
SELECT 'products',          COUNT(*) FROM products
UNION ALL
SELECT 'admin_modules',     COUNT(*) FROM admin_modules
UNION ALL
SELECT 'public_pages',      COUNT(*) FROM public_pages
UNION ALL
SELECT 'site_settings',     COUNT(*) FROM site_settings
UNION ALL
SELECT 'users (conservés)', COUNT(*) FROM users
UNION ALL
SELECT 'roles',             COUNT(*) FROM roles
ORDER BY table_name;
