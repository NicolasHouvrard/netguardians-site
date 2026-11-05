package com.logicielpourtous.site.features.site;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.transaction.annotation.Transactional;

@Controller
@RequestMapping("/intra/admin/reset")
@Secured("ROLE_ADMIN")
public class AdminResetController {

    private final JdbcTemplate jdbc;

    public AdminResetController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public String form(Authentication auth, Model model) {
        model.addAttribute("userEmail", auth.getName());
        return "intra/admin/reset";
    }

    @PostMapping
    @Transactional
    public String execute(@RequestParam(defaultValue = "") String confirmation,
                          Authentication auth,
                          RedirectAttributes ra) {

        String email = auth.getName();
        if (!email.equalsIgnoreCase(confirmation.trim())) {
            ra.addFlashAttribute("errorMessage",
                    "Confirmation incorrecte. Tapez exactement votre adresse e-mail.");
            return "redirect:/intra/admin/reset";
        }

        // Supprime toutes les données métier en une seule instruction
        // (PostgreSQL gère l'ordre des FK automatiquement)
        jdbc.execute("""
            TRUNCATE TABLE
                sync_logs,
                platform_connectors,
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
                supplier_invoices,
                supplier_products,
                stock_movements,
                stock_items,
                shop_order_lines,
                shop_orders,
                shop_products,
                shop_vendors,
                shop_configs,
                invoice_lines,
                invoices,
                order_lines,
                orders,
                ticket_attachments,
                time_entries,
                comments,
                requests,
                intervention_images,
                intervention_applications,
                interventions,
                subscriptions,
                subscription_plans,
                subscription_categories,
                subscription_requests,
                products,
                product_categories,
                sites,
                contacts,
                customers,
                mail_campaigns,
                appointments,
                blog_posts,
                mail_messages,
                mail_accounts,
                ninja_one_config,
                payment_configs,
                client_integrations,
                partner_esns,
                dashboard_widget_layouts,
                user_dashboard_configs,
                dashboard_module_links,
                intra_dashboards,
                intra_widgets,
                team_members,
                teams
            RESTART IDENTITY
            """);

        // Supprime les utilisateurs portail (ROLE_CLIENT uniquement)
        jdbc.execute("""
            DELETE FROM users_roles
            WHERE user_id IN (
                SELECT u.id FROM users u
                WHERE NOT EXISTS (
                    SELECT 1 FROM users_roles ur
                    JOIN roles r ON r.id = ur.role_id
                    WHERE ur.user_id = u.id
                      AND r.name IN (
                          'ROLE_ADMIN', 'ROLE_COMMERCIAL', 'ROLE_TECH',
                          'ROLE_MANAGER', 'ROLE_ACCOUNTANT', 'ROLE_USER'
                      )
                )
            )
            """);

        jdbc.execute("""
            DELETE FROM users
            WHERE NOT EXISTS (
                SELECT 1 FROM users_roles ur WHERE ur.user_id = users.id
            )
            """);

        ra.addFlashAttribute("successMessage",
                "Base de données réinitialisée. Données métier supprimées, "
                + "site public et modules conservés.");
        return "redirect:/intra/admin/reset";
    }
}
