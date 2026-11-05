package com.logicielpourtous.site.features.invoice;

import com.logicielpourtous.site.features.accounting.AccountingEntry;
import com.logicielpourtous.site.features.accounting.AccountingEntryRepository;
import com.logicielpourtous.site.features.accounting.QontoTransaction;
import com.logicielpourtous.site.features.accounting.QontoTransactionRepository;
import com.logicielpourtous.site.features.accounting.StripeTransaction;
import com.logicielpourtous.site.features.accounting.StripeTransactionRepository;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.product.ProductRepository;
import com.logicielpourtous.site.features.request.RequestRepository;
import com.logicielpourtous.site.features.user.UserRepository;
import com.logicielpourtous.site.features.vatrate.VatRate;
import com.logicielpourtous.site.features.vatrate.VatRateRepository;
import com.logicielpourtous.site.features.auth.CustomUserDetails;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.Comparator;

@Controller
@RequestMapping("/intra/invoices")
public class IntraInvoiceController {

    private final InvoiceRepository          invoiceRepository;
    private final InvoiceLineRepository      invoiceLineRepository;
    private final CustomerRepository         customerRepository;
    private final RequestRepository          requestRepository;
    private final UserRepository             userRepository;
    private final ProductRepository          productRepository;
    private final VatRateRepository          vatRateRepository;
    private final InvoiceAccountingService   invoiceAccountingService;
    private final TenantRepository           tenantRepository;
    private final AccountingEntryRepository  entryRepository;
    private final QontoTransactionRepository qontoTxRepo;
    private final StripeTransactionRepository stripeTxRepo;

    public IntraInvoiceController(InvoiceRepository invoiceRepository,
                                  InvoiceLineRepository invoiceLineRepository,
                                  CustomerRepository customerRepository,
                                  RequestRepository requestRepository,
                                  UserRepository userRepository,
                                  ProductRepository productRepository,
                                  VatRateRepository vatRateRepository,
                                  InvoiceAccountingService invoiceAccountingService,
                                  TenantRepository tenantRepository,
                                  AccountingEntryRepository entryRepository,
                                  QontoTransactionRepository qontoTxRepo,
                                  StripeTransactionRepository stripeTxRepo) {
        this.invoiceRepository       = invoiceRepository;
        this.invoiceLineRepository   = invoiceLineRepository;
        this.customerRepository      = customerRepository;
        this.requestRepository       = requestRepository;
        this.userRepository          = userRepository;
        this.productRepository       = productRepository;
        this.vatRateRepository       = vatRateRepository;
        this.invoiceAccountingService = invoiceAccountingService;
        this.tenantRepository        = tenantRepository;
        this.entryRepository         = entryRepository;
        this.qontoTxRepo             = qontoTxRepo;
        this.stripeTxRepo            = stripeTxRepo;
    }

    // ── Liste ──────────────────────────────────────────────────────────────────

    @GetMapping
    public String list(Model model) {
        model.addAttribute("invoices",  invoiceRepository.findByTypeOrderByInvoiceDateDesc(Invoice.InvoiceType.SALE));
        model.addAttribute("draft",     invoiceRepository.countByStatus(Invoice.InvoiceStatus.DRAFT));
        model.addAttribute("sent",      invoiceRepository.countByStatus(Invoice.InvoiceStatus.SENT));
        model.addAttribute("overdue",   invoiceRepository.countByStatus(Invoice.InvoiceStatus.OVERDUE));
        model.addAttribute("customers", customerRepository.findAll());
        return "intra/invoices/list";
    }

    // ── Nouvelle facture ───────────────────────────────────────────────────────

    @GetMapping("/new")
    public String newForm(@RequestParam(required = false) Long ticketId, Model model) {
        model.addAttribute("ticket",    ticketId != null
                ? requestRepository.findById(ticketId).orElse(null) : null);
        model.addAttribute("customers", customerRepository.findAll());
        return "intra/invoices/form";
    }

    @PostMapping
    public String create(@RequestParam Long customerId,
                         @RequestParam(required = false) Long ticketId,
                         @RequestParam(required = false) LocalDate invoiceDate,
                         @RequestParam(required = false) LocalDate dueDate,
                         @RequestParam(required = false) String notes,
                         @AuthenticationPrincipal UserDetails principal,
                         RedirectAttributes ra) {
        Invoice invoice = new Invoice();
        customerRepository.findById(customerId).ifPresent(invoice::setCustomer);
        if (ticketId != null) requestRepository.findById(ticketId).ifPresent(invoice::setTicket);
        if (principal != null) {
            userRepository.findByEmail(principal.getUsername()).ifPresent(invoice::setCreatedBy);
        }
        invoice.setInvoiceDate(invoiceDate != null ? invoiceDate : LocalDate.now());
        invoice.setDueDate(dueDate);
        invoice.setNotes(notes);
        if (invoice.getCustomer() != null && invoice.getCustomer().isVatExempt()) {
            invoice.setTaxRate(BigDecimal.ZERO);
            invoice.setVatMention(invoice.getCustomer().getDefaultVatMention());
        }
        invoice.setType(Invoice.InvoiceType.SALE);
        invoice.setStatus(Invoice.InvoiceStatus.DRAFT);
        invoiceRepository.save(invoice);
        invoice.setInvoiceNumber("FAC-" + LocalDate.now().getYear() + "-" + invoice.getId());
        invoiceRepository.save(invoice);

        ra.addFlashAttribute("successMessage", "Facture " + invoice.getInvoiceNumber() + " créée.");
        if (ticketId != null) return "redirect:/intra/tickets/" + ticketId;
        return "redirect:/intra/invoices/" + invoice.getId();
    }

    // ── Détail ─────────────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public String detail(@PathVariable Long id,
                         @AuthenticationPrincipal CustomUserDetails principal,
                         Model model) {
        Invoice invoice = invoiceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Facture introuvable : " + id));
        invoice.getLines().size();
        if (invoice.getCustomer() != null) invoice.getCustomer().getCompanyName();
        if (invoice.getTicket()   != null) { invoice.getTicket().getId(); invoice.getTicket().getTitle(); }
        if (invoice.getCreatedBy() != null) invoice.getCreatedBy().getFullName();

        Customer.FiscalZone zone = invoice.getCustomer() != null
                ? invoice.getCustomer().getFiscalZone() : Customer.FiscalZone.FRANCE;

        model.addAttribute("invoice",    invoice);
        model.addAttribute("statuses",   Invoice.InvoiceStatus.values());
        model.addAttribute("vatRates",   vatRateRepository.findByZoneAndActiveTrueOrderByRateDesc(zone));
        model.addAttribute("vatSummary", buildVatSummary(invoice));

        // ── Onglet comptabilité ──────────────────────────────────────────────
        if (principal != null && invoice.getInvoiceNumber() != null) {
            Tenant tenant = principal.getTenant();
            String prefix = "FAC-" + invoice.getInvoiceNumber();
            List<AccountingEntry> entries = entryRepository
                    .findByTenantAndReferenceStartingWithOrderByEntryDateAsc(tenant, prefix);
            entries.forEach(e -> e.getLines().size());

            List<Long> entryIds = entries.stream().map(AccountingEntry::getId).toList();
            List<QontoTransaction> qontoTxs = entryIds.isEmpty()
                    ? List.of() : qontoTxRepo.findByAccountingEntryIdIn(entryIds);
            List<StripeTransaction> stripeTxs = entryIds.isEmpty()
                    ? List.of() : stripeTxRepo.findByAccountingEntryIdIn(entryIds);

            model.addAttribute("accountingEntries", entries);
            model.addAttribute("qontoTxs",          qontoTxs);
            model.addAttribute("stripeTxs",          stripeTxs);

            // Transactions candidates au rapprochement (seulement si pas encore PAID)
            if (invoice.getStatus() != Invoice.InvoiceStatus.PAID
                    && invoice.getTotalTTC() != null) {
                BigDecimal ttc = invoice.getTotalTTC();
                List<QontoTransaction> qontoCandidates = qontoTxRepo
                        .findTop200ByTenantAndReconciledFalseAndImportStatusOrderByImportedAtDesc(tenant, "IMPORTED")
                        .stream()
                        .filter(t -> "credit".equalsIgnoreCase(t.getSide()))
                        .sorted(Comparator.comparing(t -> t.getAmount() != null
                                ? t.getAmount().subtract(ttc).abs() : BigDecimal.valueOf(Long.MAX_VALUE)))
                        .limit(10).toList();
                List<StripeTransaction> stripeCandidates = stripeTxRepo
                        .findTop200ByTenantAndReconciledFalseAndStripeStatusOrderByImportedAtDesc(tenant, "IMPORTED")
                        .stream()
                        .sorted(Comparator.comparing(t -> t.getAmount() != null
                                ? t.getAmount().subtract(ttc).abs() : BigDecimal.valueOf(Long.MAX_VALUE)))
                        .limit(10).toList();
                model.addAttribute("qontoCandidates",  qontoCandidates);
                model.addAttribute("stripeCandidates", stripeCandidates);
            }
        }

        return "intra/invoices/detail";
    }

    // ── Mettre à jour infos ────────────────────────────────────────────────────

    @PostMapping("/{id}/update")
    public String update(@PathVariable Long id,
                         @RequestParam(required = false) LocalDate invoiceDate,
                         @RequestParam(required = false) LocalDate dueDate,
                         @RequestParam(required = false) String notes,
                         @RequestParam(required = false) String vatMention,
                         RedirectAttributes ra) {
        Invoice invoice = invoiceRepository.findById(id).orElseThrow();
        invoice.setInvoiceDate(invoiceDate);
        invoice.setDueDate(dueDate);
        invoice.setNotes(notes);
        invoice.setVatMention(vatMention != null && vatMention.isBlank() ? null : vatMention);
        invoiceRepository.save(invoice);
        recalcTotals(id);
        ra.addFlashAttribute("successMessage", "Facture mise à jour.");
        return "redirect:/intra/invoices/" + id;
    }

    // ── Changer statut ─────────────────────────────────────────────────────────

    @PostMapping("/{id}/status")
    public String updateStatus(@PathVariable Long id,
                               @RequestParam Invoice.InvoiceStatus status,
                               @AuthenticationPrincipal CustomUserDetails principal,
                               RedirectAttributes ra) {
        invoiceRepository.findById(id).ifPresent(inv -> {
            Invoice.InvoiceStatus previous = inv.getStatus();
            inv.setStatus(status);
            invoiceRepository.save(inv);

            if (principal != null && principal.getTenant() != null) {
                var tenant = principal.getTenant();
                if (status == Invoice.InvoiceStatus.SENT && previous == Invoice.InvoiceStatus.DRAFT) {
                    invoiceAccountingService.onInvoiceSent(tenant, inv);
                } else if (status == Invoice.InvoiceStatus.PAID) {
                    if (previous == Invoice.InvoiceStatus.DRAFT || previous == Invoice.InvoiceStatus.SENT
                            || previous == Invoice.InvoiceStatus.OVERDUE) {
                        // Créer d'abord l'écriture VT si elle n'existe pas encore
                        invoiceAccountingService.onInvoiceSent(tenant, inv);
                    }
                    invoiceAccountingService.onInvoicePaid(tenant, inv);
                } else if (status == Invoice.InvoiceStatus.CANCELLED) {
                    invoiceAccountingService.onInvoiceCancelled(tenant, inv);
                }
            }
        });
        ra.addFlashAttribute("successMessage", "Statut mis à jour.");
        return "redirect:/intra/invoices/" + id;
    }

    // ── Lignes ─────────────────────────────────────────────────────────────────

    @PostMapping("/{id}/lines")
    public String addLine(@PathVariable Long id,
                          @RequestParam(required = false) Long productId,
                          @RequestParam String description,
                          @RequestParam(defaultValue = "1") Integer quantity,
                          @RequestParam BigDecimal unitPrice,
                          @RequestParam(required = false) Long vatRateId,
                          @RequestParam(required = false) BigDecimal costPrice,
                          RedirectAttributes ra) {
        Invoice invoice = invoiceRepository.findById(id).orElseThrow();
        InvoiceLine line = new InvoiceLine();
        line.setInvoice(invoice);
        line.setDescription(description);
        line.setQuantity(quantity);
        line.setUnitPrice(unitPrice);
        line.setTotalHT(unitPrice.multiply(BigDecimal.valueOf(quantity)));
        if (productId != null) productRepository.findById(productId).ifPresent(line::setProduct);

        if (vatRateId != null) {
            vatRateRepository.findById(vatRateId).ifPresent(vr -> {
                line.setVatRate(vr.getRate());
                line.setVatRateLabel(vr.getName());
            });
        } else {
            Customer.FiscalZone zone = invoice.getCustomer() != null
                    ? invoice.getCustomer().getFiscalZone() : Customer.FiscalZone.FRANCE;
            vatRateRepository.findFirstByZoneAndIsDefaultTrueAndActiveTrue(zone).ifPresent(vr -> {
                line.setVatRate(vr.getRate());
                line.setVatRateLabel(vr.getName());
            });
        }

        if (costPrice != null && costPrice.compareTo(BigDecimal.ZERO) > 0) {
            line.setCostPrice(costPrice);
            line.setTotalCost(costPrice.multiply(BigDecimal.valueOf(quantity)));
        }

        invoiceLineRepository.save(line);
        recalcTotals(id);
        ra.addFlashAttribute("successMessage", "Ligne ajoutée.");
        return "redirect:/intra/invoices/" + id;
    }

    @PostMapping("/{id}/lines/{lineId}/update")
    public String updateLine(@PathVariable Long id,
                             @PathVariable Long lineId,
                             @RequestParam String description,
                             @RequestParam(defaultValue = "1") Integer quantity,
                             @RequestParam BigDecimal unitPrice,
                             @RequestParam(required = false) Long vatRateId,
                             @RequestParam(required = false) BigDecimal costPrice,
                             RedirectAttributes ra) {
        invoiceLineRepository.findById(lineId)
                .filter(l -> l.getInvoice().getId().equals(id))
                .ifPresent(line -> {
                    line.setDescription(description);
                    line.setQuantity(quantity);
                    line.setUnitPrice(unitPrice);
                    line.setTotalHT(unitPrice.multiply(BigDecimal.valueOf(quantity)));
                    if (vatRateId != null) {
                        vatRateRepository.findById(vatRateId).ifPresent(vr -> {
                            line.setVatRate(vr.getRate());
                            line.setVatRateLabel(vr.getName());
                        });
                    }
                    if (costPrice != null && costPrice.compareTo(BigDecimal.ZERO) > 0) {
                        line.setCostPrice(costPrice);
                        line.setTotalCost(costPrice.multiply(BigDecimal.valueOf(quantity)));
                    } else {
                        line.setCostPrice(null);
                        line.setTotalCost(null);
                    }
                    invoiceLineRepository.save(line);
                });
        recalcTotals(id);
        ra.addFlashAttribute("successMessage", "Ligne mise à jour.");
        return "redirect:/intra/invoices/" + id;
    }

    @PostMapping("/{id}/lines/{lineId}/delete")
    public String deleteLine(@PathVariable Long id,
                             @PathVariable Long lineId,
                             RedirectAttributes ra) {
        invoiceLineRepository.findById(lineId)
                .filter(l -> l.getInvoice().getId().equals(id))
                .ifPresent(invoiceLineRepository::delete);
        recalcTotals(id);
        ra.addFlashAttribute("successMessage", "Ligne supprimée.");
        return "redirect:/intra/invoices/" + id;
    }

    // ── Génération manuelle écriture VT ───────────────────────────────────────

    @PostMapping("/{id}/generate-vt")
    @Transactional
    public String generateVt(@PathVariable Long id,
                             @AuthenticationPrincipal CustomUserDetails principal,
                             RedirectAttributes ra) {
        Invoice invoice = invoiceRepository.findById(id).orElseThrow();
        invoiceAccountingService.onInvoiceSent(principal.getTenant(), invoice);
        ra.addFlashAttribute("successMessage", "Écriture de vente (VT) générée.");
        return "redirect:/intra/invoices/" + id;
    }

    // ── Rapprochement bancaire ─────────────────────────────────────────────────

    @PostMapping("/{id}/reconcile/qonto/{txId}")
    @Transactional
    public String reconcileQonto(@PathVariable Long id,
                                 @PathVariable Long txId,
                                 @AuthenticationPrincipal CustomUserDetails principal,
                                 RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        Invoice invoice = invoiceRepository.findById(id).orElseThrow();
        QontoTransaction tx = qontoTxRepo.findByIdAndTenant(txId, tenant).orElseThrow();

        invoiceAccountingService.onInvoiceSent(tenant, invoice);
        invoiceAccountingService.onInvoicePaid(tenant, invoice);

        String bqRef = "FAC-" + invoice.getInvoiceNumber() + "-BQ";
        entryRepository.findByTenantAndReferenceStartingWithOrderByEntryDateAsc(tenant, bqRef)
                .stream().findFirst().ifPresent(e -> tx.setAccountingEntryId(e.getId()));

        tx.setReconciled(true);
        qontoTxRepo.save(tx);
        invoice.setStatus(Invoice.InvoiceStatus.PAID);
        invoiceRepository.save(invoice);

        ra.addFlashAttribute("successMessage", "Virement Qonto rapproché — facture passée en Payée.");
        return "redirect:/intra/invoices/" + id;
    }

    @PostMapping("/{id}/reconcile/stripe/{txId}")
    @Transactional
    public String reconcileStripe(@PathVariable Long id,
                                  @PathVariable Long txId,
                                  @AuthenticationPrincipal CustomUserDetails principal,
                                  RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        Invoice invoice = invoiceRepository.findById(id).orElseThrow();
        StripeTransaction tx = stripeTxRepo.findByIdAndTenant(txId, tenant).orElseThrow();

        invoiceAccountingService.onInvoiceSent(tenant, invoice);
        invoiceAccountingService.onInvoicePaid(tenant, invoice);

        String bqRef = "FAC-" + invoice.getInvoiceNumber() + "-BQ";
        entryRepository.findByTenantAndReferenceStartingWithOrderByEntryDateAsc(tenant, bqRef)
                .stream().findFirst().ifPresent(e -> tx.setAccountingEntryId(e.getId()));

        tx.setReconciled(true);
        stripeTxRepo.save(tx);
        invoice.setStatus(Invoice.InvoiceStatus.PAID);
        invoiceRepository.save(invoice);

        ra.addFlashAttribute("successMessage", "Paiement Stripe rapproché — facture passée en Payée.");
        return "redirect:/intra/invoices/" + id;
    }

    // ── Vue imprimable ─────────────────────────────────────────────────────────

    @GetMapping("/{id}/print")
    @Transactional(readOnly = true)
    public String print(@PathVariable Long id,
                        @AuthenticationPrincipal CustomUserDetails principal,
                        Model model) {
        Invoice invoice = invoiceRepository.findById(id).orElseThrow();
        invoice.getLines().size();
        if (invoice.getCustomer() != null) invoice.getCustomer().getCompanyName();
        model.addAttribute("invoice", invoice);
        model.addAttribute("vatSummary", buildVatSummary(invoice));
        if (principal != null)
            model.addAttribute("tenant", tenantRepository.findById(principal.getTenant().getId()).orElse(null));
        return "intra/invoices/print";
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private void recalcTotals(Long invoiceId) {
        invoiceRepository.findById(invoiceId).ifPresent(invoice -> {
            BigDecimal ht = BigDecimal.ZERO, tva = BigDecimal.ZERO, cost = BigDecimal.ZERO;
            for (InvoiceLine line : invoice.getLines()) {
                BigDecimal lineHT = line.getTotalHT() != null ? line.getTotalHT() : BigDecimal.ZERO;
                BigDecimal rate   = line.getVatRate() != null ? line.getVatRate()  : BigDecimal.ZERO;
                BigDecimal lineTVA = lineHT.multiply(rate.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP))
                        .setScale(2, RoundingMode.HALF_UP);
                line.setTotalTVA(lineTVA);
                invoiceLineRepository.save(line);
                ht   = ht.add(lineHT);
                tva  = tva.add(lineTVA);
                if (line.getTotalCost() != null) cost = cost.add(line.getTotalCost());
            }
            invoice.setTotalHT(ht);
            invoice.setTotalTVA(tva);
            invoice.setTotalTTC(ht.add(tva));
            invoice.setTotalCost(cost);
            invoiceRepository.save(invoice);
        });
    }

    /** Regroupe les lignes par taux TVA pour l'encart récapitulatif */
    private List<Map<String, Object>> buildVatSummary(Invoice invoice) {
        Map<BigDecimal, BigDecimal[]> map = new LinkedHashMap<>();
        for (InvoiceLine line : invoice.getLines()) {
            BigDecimal rate = line.getVatRate() != null ? line.getVatRate() : BigDecimal.ZERO;
            BigDecimal ht   = line.getTotalHT()  != null ? line.getTotalHT()  : BigDecimal.ZERO;
            BigDecimal tva  = line.getTotalTVA() != null ? line.getTotalTVA() : BigDecimal.ZERO;
            map.merge(rate, new BigDecimal[]{ht, tva},
                    (a, b) -> new BigDecimal[]{a[0].add(b[0]), a[1].add(b[1])});
        }
        List<Map<String, Object>> result = new ArrayList<>();
        map.forEach((rate, vals) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rate",  rate);
            row.put("baseHT", vals[0]);
            row.put("tva",   vals[1]);
            row.put("ttc",   vals[0].add(vals[1]));
            result.add(row);
        });
        return result;
    }
}
