package com.logicielpourtous.site.features.intra;

import com.logicielpourtous.site.features.accounting.AccountingAccountRepository;
import com.logicielpourtous.site.features.accounting.AccountingBatchRepository;
import com.logicielpourtous.site.features.accounting.AccountingEntryLineRepository;
import com.logicielpourtous.site.features.accounting.QontoTransactionRepository;
import com.logicielpourtous.site.features.accounting.StripeTransactionRepository;
import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.campaign.MailCampaign;
import com.logicielpourtous.site.features.campaign.MailCampaignRepository;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.crm.quote.QuoteLineRepository;
import com.logicielpourtous.site.features.crm.quote.QuoteRepository;
import com.logicielpourtous.site.features.crm.quote.QuoteStatus;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.dashboard.UserDashboardConfig;
import com.logicielpourtous.site.features.intervention.Intervention;
import com.logicielpourtous.site.features.intervention.InterventionRepository;
import com.logicielpourtous.site.features.invoice.Invoice;
import com.logicielpourtous.site.features.invoice.InvoiceRepository;
import com.logicielpourtous.site.features.order.Order;
import com.logicielpourtous.site.features.order.OrderRepository;
import com.logicielpourtous.site.features.purchase.SupplierInvoice;
import com.logicielpourtous.site.features.purchase.SupplierInvoiceRepository;
import com.logicielpourtous.site.features.request.RequestRepository;
import com.logicielpourtous.site.features.request.RequestStatus;
import com.logicielpourtous.site.features.shop.ShopOrder;
import com.logicielpourtous.site.features.shop.ShopOrderRepository;
import com.logicielpourtous.site.features.subscription.SubscriptionRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;

@Service
public class IntraWidgetService {

    public record SubValue(String label, String value, String color) {}

    public record WidgetData(String widgetKey, String value, String label,
                             String icon, String color, String link,
                             List<SubValue> subValues,
                             List<List<String>> rows,
                             String widgetStyle) {
        /** Widget KPI simple */
        public WidgetData(String widgetKey, String value, String label,
                          String icon, String color, String link) {
            this(widgetKey, value, label, icon, color, link, List.of(), List.of(), null);
        }
        /** Widget multi-valeurs */
        public WidgetData(String widgetKey, String value, String label,
                          String icon, String color, String link, List<SubValue> subValues) {
            this(widgetKey, value, label, icon, color, link, subValues, List.of(), null);
        }
        /** Widget liste (compat) */
        public WidgetData(String widgetKey, String value, String label,
                          String icon, String color, String link,
                          List<SubValue> subValues, List<List<String>> rows) {
            this(widgetKey, value, label, icon, color, link, subValues, rows, null);
        }
        public boolean hasSubValues() { return subValues != null && !subValues.isEmpty(); }
        public boolean hasList()      { return rows != null && !rows.isEmpty(); }
        public boolean isLinkList()   { return "links".equals(widgetStyle); }
    }

    private static final List<RequestStatus> OPEN_STATUSES =
            List.of(RequestStatus.NEW, RequestStatus.IN_PROGRESS);

    // ── Repositories ──────────────────────────────────────────────────────────
    private final IntraWidgetRepository        widgetRepository;
    private final QuoteRepository              quoteRepository;
    private final QuoteLineRepository          quoteLineRepository;
    private final RequestRepository            requestRepository;
    private final CustomerRepository           customerRepository;
    private final ShopOrderRepository          shopOrderRepository;
    private final SubscriptionRepository       subscriptionRepository;
    private final InterventionRepository       interventionRepository;
    private final InvoiceRepository            invoiceRepository;
    private final MailCampaignRepository       campaignRepository;
    private final OrderRepository              orderRepository;
    private final QontoTransactionRepository   qontoTransactionRepository;
    private final StripeTransactionRepository  stripeTransactionRepository;
    private final AccountingBatchRepository    accountingBatchRepository;
    private final SupplierInvoiceRepository    supplierInvoiceRepository;
    private final AccountingAccountRepository  accountingAccountRepository;
    private final AccountingEntryLineRepository entryLineRepository;

    /** widgetKey → valeur unique */
    private final Map<String, Supplier<String>>             registry      = new HashMap<>();
    /** widgetKey → sous-valeurs multiples (FAC_CLI, FAC_FOU…) */
    private final Map<String, Supplier<List<SubValue>>>     multiRegistry = new HashMap<>();
    /** widgetKey → lignes de tableau (FAC_CLI_LIST, FAC_FOU_LIST…) */
    private final Map<String, Supplier<List<List<String>>>> listRegistry  = new HashMap<>();
    /** widgetKey → lignes de liens cliquables (RAPPORTS_FISCAUX, ACTIONS_COMPTA…) */
    private final Map<String, Supplier<List<List<String>>>> linkListRegistry = new HashMap<>();

    public IntraWidgetService(IntraWidgetRepository widgetRepository,
                              QuoteRepository quoteRepository,
                              QuoteLineRepository quoteLineRepository,
                              RequestRepository requestRepository,
                              CustomerRepository customerRepository,
                              ShopOrderRepository shopOrderRepository,
                              SubscriptionRepository subscriptionRepository,
                              InterventionRepository interventionRepository,
                              InvoiceRepository invoiceRepository,
                              MailCampaignRepository campaignRepository,
                              OrderRepository orderRepository,
                              QontoTransactionRepository qontoTransactionRepository,
                              StripeTransactionRepository stripeTransactionRepository,
                              AccountingBatchRepository accountingBatchRepository,
                              SupplierInvoiceRepository supplierInvoiceRepository,
                              AccountingAccountRepository accountingAccountRepository,
                              AccountingEntryLineRepository entryLineRepository) {
        this.widgetRepository            = widgetRepository;
        this.quoteRepository             = quoteRepository;
        this.quoteLineRepository         = quoteLineRepository;
        this.requestRepository           = requestRepository;
        this.customerRepository          = customerRepository;
        this.shopOrderRepository         = shopOrderRepository;
        this.subscriptionRepository      = subscriptionRepository;
        this.interventionRepository      = interventionRepository;
        this.invoiceRepository           = invoiceRepository;
        this.campaignRepository          = campaignRepository;
        this.orderRepository             = orderRepository;
        this.qontoTransactionRepository  = qontoTransactionRepository;
        this.stripeTransactionRepository = stripeTransactionRepository;
        this.accountingBatchRepository   = accountingBatchRepository;
        this.supplierInvoiceRepository   = supplierInvoiceRepository;
        this.accountingAccountRepository = accountingAccountRepository;
        this.entryLineRepository         = entryLineRepository;
    }

    @PostConstruct
    private void initRegistry() {
        reg("OPEN_TICKETS",
                () -> String.valueOf(requestRepository.countByStatusIn(OPEN_STATUSES)));
        reg("INTERVENTIONS_IN_PROGRESS",
                () -> String.valueOf(interventionRepository.countByStatus(Intervention.InterventionStatus.IN_PROGRESS)));
        reg("INTERVENTIONS_A_FACTURER",
                () -> String.valueOf(interventionRepository
                        .findByStatusAndLinkedInvoiceIsNullOrderByDateAsc(Intervention.InterventionStatus.COMPLETED)
                        .size()));
        reg("CUSTOMERS",
                () -> String.valueOf(customerRepository.count()));
        reg("COMMANDES_EN_COURS",
                () -> String.valueOf(
                        orderRepository.countByStatus(Order.OrderStatus.CONFIRMED)
                      + orderRepository.countByStatus(Order.OrderStatus.IN_PROGRESS)));
        reg("PENDING_SHOP_ORDERS",
                () -> String.valueOf(shopOrderRepository.countByStatus(ShopOrder.OrderStatus.PENDING_VALIDATION)));
        reg("ACTIVE_SUBSCRIPTIONS",
                () -> String.valueOf(subscriptionRepository.countByActiveTrue()));
        reg("CA_ENCAISSE",
                () -> formatAmount(invoiceRepository.sumPaidSales()));
        reg("PENDING_INVOICES",
                () -> String.valueOf(invoiceRepository.countByStatus(Invoice.InvoiceStatus.SENT)));
        reg("FACTURES_EN_RETARD",
                () -> String.valueOf(invoiceRepository.countByStatus(Invoice.InvoiceStatus.OVERDUE)));
        reg("CAMPAGNES_ACTIVES",
                () -> String.valueOf(campaignRepository.countByStatus(MailCampaign.CampaignStatus.SENT)));
        reg("QONTO_A_RAPPROCHER", () -> {
            Tenant t = currentTenant();
            return t != null ? String.valueOf(qontoTransactionRepository.countByTenantAndReconciledFalse(t)) : "—";
        });
        reg("STRIPE_A_RAPPROCHER", () -> {
            Tenant t = currentTenant();
            return t != null ? String.valueOf(stripeTransactionRepository.countByTenantAndReconciledFalse(t)) : "—";
        });
        reg("OD_EN_ATTENTE", () -> {
            Tenant t = currentTenant();
            return t != null ? String.valueOf(accountingBatchRepository.countByTenantAndStatus(t, "DRAFT")) : "—";
        });

        // ── Factures clients (multi-valeurs) ─────────────────────────────
        regMulti("FAC_CLI", () -> List.of(
                new SubValue("À payer",   String.valueOf(invoiceRepository.countByStatus(Invoice.InvoiceStatus.SENT)),    "#3b82f6"),
                new SubValue("En retard", String.valueOf(invoiceRepository.countByStatus(Invoice.InvoiceStatus.OVERDUE)), "#dc2626"),
                new SubValue("Brouillon", String.valueOf(invoiceRepository.countByStatus(Invoice.InvoiceStatus.DRAFT)),   "#94a3b8")
        ));

        // ── Liste 10 dernières factures clients ───────────────────────────
        regList("FAC_CLI_LIST", () ->
                invoiceRepository.findTop10ByOrderByCreatedAtDesc().stream()
                        .map(inv -> List.of(
                                inv.getInvoiceNumber() != null ? inv.getInvoiceNumber() : "—",
                                inv.getCustomer() != null ? inv.getCustomer().getCompanyName() : "—",
                                formatAmount(inv.getTotalTTC()),
                                inv.getStatus().label
                        ))
                        .collect(java.util.stream.Collectors.toList())
        );

        // ── Liste 10 dernières factures fournisseurs (tenant-aware) ──────
        regList("FAC_FOU_LIST", () -> {
            Tenant t = currentTenant();
            if (t == null) return List.of();
            return supplierInvoiceRepository.findTop10ByTenantOrderByCreatedAtDesc(t).stream()
                    .map(inv -> List.of(
                            inv.getInvoiceNumber() != null ? inv.getInvoiceNumber() : "—",
                            inv.getVendorName() != null ? inv.getVendorName() : "—",
                            formatAmount(inv.getTotalTTC()),
                            inv.getStatus().getLabel()
                    ))
                    .collect(java.util.stream.Collectors.toList());
        });

        // ── Factures fournisseurs (multi-valeurs, tenant-aware) ───────────
        regMulti("FAC_FOU", () -> {
            Tenant t = currentTenant();
            if (t == null) return List.of(
                    new SubValue("À payer",   "—", "#f59e0b"),
                    new SubValue("En retard", "—", "#ef4444"),
                    new SubValue("Brouillon", "—", "#64748b"));
            return List.of(
                    new SubValue("À payer",   String.valueOf(supplierInvoiceRepository.countByTenantAndStatus(t, SupplierInvoice.Status.TO_PAY)), "#f59e0b"),
                    new SubValue("En retard", String.valueOf(supplierInvoiceRepository.countByTenantAndStatusAndDueDateBefore(t, SupplierInvoice.Status.TO_PAY, LocalDate.now())), "#ef4444"),
                    new SubValue("Brouillon", String.valueOf(supplierInvoiceRepository.countByTenantAndStatus(t, SupplierInvoice.Status.DRAFT)), "#64748b")
            );
        });

        // ── Factures clients enrichies (montants + compteurs) ─────────────
        regMulti("COMPTA_CLI", () -> List.of(
                new SubValue("CA encaissé",      formatAmount(invoiceRepository.sumPaidSales()),                                     "#10b981"),
                new SubValue("Impayés en retard",formatAmount(invoiceRepository.sumOverdue()),                                        "#ef4444"),
                new SubValue("Brouillons",       String.valueOf(invoiceRepository.countByStatus(Invoice.InvoiceStatus.DRAFT)),        "#94a3b8"),
                new SubValue("Envoyées",         String.valueOf(invoiceRepository.countByStatus(Invoice.InvoiceStatus.SENT)),         "#3b82f6"),
                new SubValue("Réglées",          String.valueOf(invoiceRepository.countByStatus(Invoice.InvoiceStatus.PAID)),         "#10b981"),
                new SubValue("En retard",        String.valueOf(invoiceRepository.countByStatus(Invoice.InvoiceStatus.OVERDUE)),      "#dc2626")
        ));

        // ── Factures fournisseurs enrichies (montants + compteurs) ────────
        regMulti("COMPTA_FOU", () -> {
            Tenant t = currentTenant();
            if (t == null) return List.of(
                    new SubValue("Dépenses réglées", "—", "#10b981"),
                    new SubValue("À régler",         "—", "#ef4444"),
                    new SubValue("Brouillons",       "—", "#94a3b8"),
                    new SubValue("À payer",          "—", "#f59e0b"),
                    new SubValue("Payées",           "—", "#10b981"));
            return List.of(
                    new SubValue("Dépenses réglées", formatAmount(supplierInvoiceRepository.sumTotalTTCByTenantAndStatus(t, SupplierInvoice.Status.PAID)),   "#10b981"),
                    new SubValue("À régler",         formatAmount(supplierInvoiceRepository.sumTotalTTCByTenantAndStatus(t, SupplierInvoice.Status.TO_PAY)), "#ef4444"),
                    new SubValue("Brouillons",       String.valueOf(supplierInvoiceRepository.countByTenantAndStatus(t, SupplierInvoice.Status.DRAFT)),      "#94a3b8"),
                    new SubValue("À payer",          String.valueOf(supplierInvoiceRepository.countByTenantAndStatus(t, SupplierInvoice.Status.TO_PAY)),     "#f59e0b"),
                    new SubValue("Payées",           String.valueOf(supplierInvoiceRepository.countByTenantAndStatus(t, SupplierInvoice.Status.PAID)),       "#10b981")
            );
        });

        // ── Comptes de trésorerie ─────────────────────────────────────────
        regList("TRESORERIE", () -> {
            Tenant t = currentTenant();
            if (t == null) return List.of();
            return accountingAccountRepository.findByTenantAndActiveTrueOrderByNumberAsc(t).stream()
                    .filter(a -> a.getNumber() != null
                            && (a.getNumber().startsWith("51") || a.getNumber().startsWith("53")))
                    .map(acc -> {
                        BigDecimal balance = entryLineRepository.getBalanceByAccountNumberAndTenant(acc.getNumber(), t);
                        long unreconciled = qontoTransactionRepository.countByTenantAndBankAccountNumberAndReconciledFalseAndImportStatus(t, acc.getNumber(), "IMPORTED")
                                + stripeTransactionRepository.countByTenantAndBankAccountNumberAndReconciledFalseAndStripeStatus(t, acc.getNumber(), "IMPORTED");
                        String balStr = balance != null ? formatAmount(balance) : "—";
                        String rapStr = unreconciled > 0 ? unreconciled + " à rapprocher" : "À jour";
                        return List.of(
                                acc.getLabel() != null ? acc.getLabel() : acc.getNumber(),
                                acc.getNumber(),
                                balStr,
                                rapStr);
                    })
                    .collect(java.util.stream.Collectors.toList());
        });

        // ── Rapports fiscaux (liens rapides) ──────────────────────────────
        regLinkList("RAPPORTS_FISCAUX", () -> List.of(
                List.of("fas fa-scale-balanced", "Balance générale",    "/intra/accounting/reports/balance"),
                List.of("fas fa-book-open",       "Grand livre",         "/intra/accounting/reports/grand-livre"),
                List.of("fas fa-landmark",         "Bilan",               "/intra/accounting/reports/bilan"),
                List.of("fas fa-chart-line",       "Compte de résultat",  "/intra/accounting/reports/compte-resultat"),
                List.of("fas fa-file-alt",         "Liasses fiscales",    "/intra/accounting/fiscal")
        ));

        // ── Actions rapides comptabilité ──────────────────────────────────
        regLinkList("ACTIONS_COMPTA", () -> List.of(
                List.of("fas fa-plus",          "Nouvelle facture client",       "/intra/invoices/new"),
                List.of("fas fa-upload",         "Importer fact. fournisseur",   "/intra/supplier-invoices/upload"),
                List.of("fas fa-pen-to-square",  "Journal des écritures",        "/intra/accounting/entries"),
                List.of("fas fa-layer-group",    "Lots OD",                      "/intra/accounting/batches")
        ));

        // ── Widgets CRM commercial ────────────────────────────────────────────
        regMulti("QUOTES_DASHBOARD", () -> List.of(
                new SubValue("En attente HT", formatAmount(quoteLineRepository.sumTotalHTByQuoteStatuses(List.of(QuoteStatus.BROUILLON, QuoteStatus.ENVOYE))), "#0ea5e9"),
                new SubValue("Brouillon",     String.valueOf(quoteRepository.countByStatus(QuoteStatus.BROUILLON)), "#94a3b8"),
                new SubValue("Envoyés",       String.valueOf(quoteRepository.countByStatus(QuoteStatus.ENVOYE)),    "#0ea5e9"),
                new SubValue("Acceptés",      String.valueOf(quoteRepository.countByStatus(QuoteStatus.ACCEPTE)),   "#10b981"),
                new SubValue("Refusés",       String.valueOf(quoteRepository.countByStatus(QuoteStatus.REFUSE)),    "#ef4444"),
                new SubValue("Expirés",       String.valueOf(quoteRepository.countByStatus(QuoteStatus.EXPIRE)),    "#6b7280")
        ));
        regMulti("ORDERS_DASHBOARD", () -> List.of(
                new SubValue("En cours HT",  formatAmount(orderRepository.sumTotalHTByStatuses(
                        List.of(Order.OrderStatus.PENDING, Order.OrderStatus.CONFIRMED, Order.OrderStatus.IN_PROGRESS))), "#0ea5e9"),
                new SubValue("En attente",   String.valueOf(orderRepository.countByStatus(Order.OrderStatus.PENDING)),     "#94a3b8"),
                new SubValue("Confirmées",   String.valueOf(orderRepository.countByStatus(Order.OrderStatus.CONFIRMED)),   "#3b82f6"),
                new SubValue("En cours",     String.valueOf(orderRepository.countByStatus(Order.OrderStatus.IN_PROGRESS)), "#f59e0b"),
                new SubValue("Livrées",      String.valueOf(orderRepository.countByStatus(Order.OrderStatus.DELIVERED)),   "#10b981"),
                new SubValue("Annulées",     String.valueOf(orderRepository.countByStatus(Order.OrderStatus.CANCELLED)),   "#ef4444")
        ));
        reg("PROSPECTS_PIPELINE",
                () -> String.valueOf(customerRepository.findProspects().size()));
        reg("QUOTES_EN_ATTENTE",
                () -> String.valueOf(quoteRepository.countByStatus(QuoteStatus.ENVOYE)));
        reg("TAUX_CONVERSION", () -> {
            long total    = quoteRepository.count();
            long accepted = quoteRepository.countAccepted();
            return total == 0 ? "—" : String.format("%.0f %%", accepted * 100.0 / total);
        });
        reg("PIPELINE_VALUE", () -> formatAmount(
                customerRepository.findProspects().stream()
                        .filter(p -> p.getEstimatedValue() != null)
                        .map(p -> p.getEstimatedValue())
                        .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)));
    }

    private void reg(String key, Supplier<String> fn)                  { registry.put(key, fn); }
    private void regMulti(String key, Supplier<List<SubValue>> fn)     { multiRegistry.put(key, fn); }
    private void regList(String key, Supplier<List<List<String>>> fn)  { listRegistry.put(key, fn); }
    private void regLinkList(String key, Supplier<List<List<String>>> fn) { linkListRegistry.put(key, fn); }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Tous les widgets actifs accessibles pour les rôles donnés. */
    public List<IntraWidget> availableWidgets(Set<String> roles) {
        return widgetRepository.findByActiveTrueOrderByDisplayOrderAsc().stream()
                .filter(w -> w.isAllowedFor(roles))
                .toList();
    }

    /** Widgets actifs selon la config utilisateur, ou défauts par rôle. */
    public List<WidgetData> activeWidgets(UserDashboardConfig config, Set<String> roles) {
        String codes = config != null ? config.getIntraWidgetCodes() : "";
        if (codes == null || codes.isBlank()) return defaultWidgets(roles);

        List<WidgetData> result = new ArrayList<>();
        for (String key : codes.split(",")) {
            key = key.trim();
            if (key.isBlank()) continue;
            widgetRepository.findByWidgetKey(key).ifPresent(w -> {
                if (w.isActive() && w.isAllowedFor(roles)) result.add(compute(w));
            });
        }
        return result.isEmpty() ? defaultWidgets(roles) : result;
    }

    /** Calcule la valeur d'un widget depuis la BDD. */
    public WidgetData compute(IntraWidget widget) {
        String key = widget.getWidgetKey();

        // Widget liens cliquables (rapports, actions rapides…)
        Supplier<List<List<String>>> linkSupplier = linkListRegistry.get(key);
        if (linkSupplier != null) {
            List<List<String>> rows;
            try { rows = linkSupplier.get(); } catch (Exception e) { rows = List.of(); }
            return new WidgetData(key, String.valueOf(rows.size()), widget.getLabel(),
                    widget.getIcon(), widget.getColor(), widget.getLink(), List.of(), rows, "links");
        }

        // Widget liste (tableau de lignes)
        Supplier<List<List<String>>> listSupplier = listRegistry.get(key);
        if (listSupplier != null) {
            List<List<String>> rows;
            try { rows = listSupplier.get(); } catch (Exception e) { rows = List.of(); }
            return new WidgetData(key, String.valueOf(rows.size()), widget.getLabel(),
                    widget.getIcon(), widget.getColor(), widget.getLink(), List.of(), rows);
        }

        // Widget multi-valeurs
        Supplier<List<SubValue>> multiSupplier = multiRegistry.get(key);
        if (multiSupplier != null) {
            List<SubValue> subs;
            try { subs = multiSupplier.get(); } catch (Exception e) { subs = List.of(); }
            return new WidgetData(key, "", widget.getLabel(),
                    widget.getIcon(), widget.getColor(), widget.getLink(), subs);
        }

        // Widget KPI simple
        String value = computeValue(key);
        return new WidgetData(key, value,
                widget.getLabel(), widget.getIcon(), widget.getColor(), widget.getLink());
    }

    /** Calcule la valeur d'un widget par sa clé. */
    public WidgetData compute(String widgetKey) {
        return widgetRepository.findByWidgetKey(widgetKey)
                .map(this::compute)
                .orElse(new WidgetData(widgetKey, "—", widgetKey, "", "", ""));
    }

    public String computeValue(String widgetKey) {
        Supplier<String> fn = registry.get(widgetKey);
        if (fn == null) return "—";
        try { return fn.get(); } catch (Exception e) { return "—"; }
    }

    // ── Defaults by role ──────────────────────────────────────────────────────

    private List<WidgetData> defaultWidgets(Set<String> roles) {
        List<String> keys;
        if (roles.contains("ROLE_ADMIN")) {
            keys = widgetRepository.findByActiveTrueOrderByDisplayOrderAsc()
                    .stream().map(IntraWidget::getWidgetKey).toList();
        } else if (roles.contains("ROLE_COMPTABLE")) {
            keys = List.of("CA_ENCAISSE","PENDING_INVOICES","FACTURES_EN_RETARD",
                           "QONTO_A_RAPPROCHER","STRIPE_A_RAPPROCHER","OD_EN_ATTENTE",
                           "ACTIVE_SUBSCRIPTIONS");
        } else if (roles.contains("ROLE_TECHNICIEN")) {
            keys = List.of("OPEN_TICKETS","INTERVENTIONS_IN_PROGRESS","INTERVENTIONS_A_FACTURER");
        } else if (roles.contains("ROLE_COMMERCIAL")) {
            keys = List.of("PROSPECTS_PIPELINE","QUOTES_EN_ATTENTE","TAUX_CONVERSION","PIPELINE_VALUE",
                           "CUSTOMERS","COMMANDES_EN_COURS","ACTIVE_SUBSCRIPTIONS","OPEN_TICKETS");
        } else if (roles.contains("ROLE_MARKETING")) {
            keys = List.of("CAMPAGNES_ACTIVES","CUSTOMERS");
        } else {
            keys = List.of("OPEN_TICKETS","CUSTOMERS","INTERVENTIONS_IN_PROGRESS","ACTIVE_SUBSCRIPTIONS");
        }
        return keys.stream()
                .map(key -> widgetRepository.findByWidgetKey(key))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(w -> w.isActive() && w.isAllowedFor(roles))
                .map(this::compute)
                .toList();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Tenant currentTenant() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CustomUserDetails ud) {
            return ud.getTenant();
        }
        return null;
    }

    private static String formatAmount(BigDecimal amount) {
        if (amount == null) return "—";
        long val = amount.longValue();
        if (val >= 1_000_000) return String.format("%.1f M€", val / 1_000_000.0);
        if (val >= 1_000)     return String.format("%,d k€", val / 1_000).replace(",", " ");
        return val + " €";
    }
}
