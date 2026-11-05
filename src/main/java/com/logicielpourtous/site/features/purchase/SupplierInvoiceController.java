package com.logicielpourtous.site.features.purchase;

import com.logicielpourtous.site.features.accounting.AccountingAccountRepository;
import com.logicielpourtous.site.features.accounting.AccountingEntryRepository;
import com.logicielpourtous.site.features.accounting.QontoTransaction;
import com.logicielpourtous.site.features.accounting.QontoTransactionRepository;
import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.supplier.IngramInvoiceService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.UUID;

@Controller
@RequestMapping("/intra/supplier-invoices")
public class SupplierInvoiceController {

    private final SupplierInvoiceRepository invoiceRepo;
    private final SupplierInvoiceAccountingService accountingService;
    private final AccountingAccountRepository accountRepo;
    private final AccountingEntryRepository entryRepo;
    private final QontoTransactionRepository qontoTxRepo;
    private final MindeeService mindeeService;
    private final GoogleDocumentAiService googleAiService;
    private final IngramInvoiceService ingramInvoiceService;

    @Value("${app.upload-dir}")
    private String uploadDir;

    public SupplierInvoiceController(SupplierInvoiceRepository invoiceRepo,
                                      SupplierInvoiceAccountingService accountingService,
                                      AccountingAccountRepository accountRepo,
                                      AccountingEntryRepository entryRepo,
                                      QontoTransactionRepository qontoTxRepo,
                                      MindeeService mindeeService,
                                      GoogleDocumentAiService googleAiService,
                                      IngramInvoiceService ingramInvoiceService) {
        this.invoiceRepo          = invoiceRepo;
        this.accountingService    = accountingService;
        this.accountRepo          = accountRepo;
        this.entryRepo            = entryRepo;
        this.qontoTxRepo          = qontoTxRepo;
        this.mindeeService        = mindeeService;
        this.googleAiService      = googleAiService;
        this.ingramInvoiceService = ingramInvoiceService;
    }

    // ── Liste ─────────────────────────────────────────────────────────────────

    @GetMapping
    public String list(@AuthenticationPrincipal CustomUserDetails principal, Model model) {
        Tenant tenant = principal.getTenant();
        model.addAttribute("invoices",      invoiceRepo.findByTenantOrderByCreatedAtDesc(tenant));
        model.addAttribute("countDraft",    invoiceRepo.countByTenantAndStatus(tenant, SupplierInvoice.Status.DRAFT));
        model.addAttribute("countToPay",    invoiceRepo.countByTenantAndStatus(tenant, SupplierInvoice.Status.TO_PAY));
        model.addAttribute("countPaid",     invoiceRepo.countByTenantAndStatus(tenant, SupplierInvoice.Status.PAID));
        model.addAttribute("mindeeReady",   mindeeService.isConfigured());
        model.addAttribute("googleAiReady", googleAiService.isConfigured());
        return "intra/supplier-invoices/list";
    }

    // ── Upload + analyse Mindee ───────────────────────────────────────────────

    @GetMapping("/upload")
    public String uploadForm(@AuthenticationPrincipal CustomUserDetails principal,
                              @RequestParam(required = false) String qontoTxId,
                              HttpSession session, Model model) {
        model.addAttribute("mindeeReady",   mindeeService.isConfigured());
        model.addAttribute("googleAiReady", googleAiService.isConfigured());
        model.addAttribute("qontoTxId", qontoTxId);
        MindeeService.MindeeResult preResult = (MindeeService.MindeeResult) session.getAttribute("mindeeResult");
        String preFilePath = (String) session.getAttribute("mindeeFilePath");
        if (preResult != null) {
            session.removeAttribute("mindeeResult");
            session.removeAttribute("mindeeFilePath");
            model.addAttribute("mindeeResult", preResult);
            model.addAttribute("savedFilePath", preFilePath);
            model.addAttribute("accounts", accountRepo.findByTenantAndActiveTrueOrderByNumberAsc(principal.getTenant()));
        }
        return "intra/supplier-invoices/upload";
    }

    @PostMapping("/analyze")
    public String analyze(@AuthenticationPrincipal CustomUserDetails principal,
                           @RequestParam("file") MultipartFile file,
                           @RequestParam(required = false) String qontoTxId,
                           HttpSession session,
                           RedirectAttributes ra) {
        if (file.isEmpty()) {
            ra.addFlashAttribute("errorMessage", "Aucun fichier sélectionné.");
            return "redirect:/intra/supplier-invoices/upload";
        }
        try {
            Tenant tenant = principal.getTenant();
            Path dir = Paths.get(uploadDir, "supplier-invoices", tenant.getSchemaName());
            Files.createDirectories(dir);
            String filename = UUID.randomUUID() + "-" + sanitize(file.getOriginalFilename());
            Path dest = dir.resolve(filename);
            file.transferTo(dest);

            MindeeService.MindeeResult result = mindeeService.parseInvoice(dest.toFile());
            session.setAttribute("mindeeResult", result);
            session.setAttribute("mindeeFilePath", "supplier-invoices/" + tenant.getSchemaName() + "/" + filename);
            session.setAttribute("mindeeOriginalFilename", file.getOriginalFilename());
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", "Erreur Mindee : " + e.getMessage());
        }
        String redirect = "/intra/supplier-invoices/upload";
        if (qontoTxId != null && !qontoTxId.isBlank()) redirect += "?qontoTxId=" + qontoTxId;
        return "redirect:" + redirect;
    }

    @PostMapping("/analyze-google")
    public String analyzeGoogle(@AuthenticationPrincipal CustomUserDetails principal,
                                 @RequestParam("file") MultipartFile file,
                                 @RequestParam(required = false) String qontoTxId,
                                 HttpSession session,
                                 RedirectAttributes ra) {
        if (file.isEmpty()) {
            ra.addFlashAttribute("errorMessage", "Aucun fichier sélectionné.");
            return "redirect:/intra/supplier-invoices/upload";
        }
        try {
            Tenant tenant = principal.getTenant();
            Path dir = Paths.get(uploadDir, "supplier-invoices", tenant.getSchemaName());
            Files.createDirectories(dir);
            String filename = UUID.randomUUID() + "-" + sanitize(file.getOriginalFilename());
            Path dest = dir.resolve(filename);
            file.transferTo(dest);

            MindeeService.MindeeResult result = googleAiService.parseInvoice(dest.toFile());
            session.setAttribute("mindeeResult", result);
            session.setAttribute("mindeeFilePath", "supplier-invoices/" + tenant.getSchemaName() + "/" + filename);
            session.setAttribute("mindeeOriginalFilename", file.getOriginalFilename());
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", "Erreur Google Document AI : " + e.getMessage());
        }
        String redirect = "/intra/supplier-invoices/upload";
        if (qontoTxId != null && !qontoTxId.isBlank()) redirect += "?qontoTxId=" + qontoTxId;
        return "redirect:" + redirect;
    }

    // ── Sauvegarde après review ───────────────────────────────────────────────

    @PostMapping("/save")
    @Transactional
    public String save(@AuthenticationPrincipal CustomUserDetails principal,
                        @RequestParam(required = false) String vendorName,
                        @RequestParam(required = false) String invoiceNumber,
                        @RequestParam(required = false) String invoiceDate,
                        @RequestParam(required = false) String dueDate,
                        @RequestParam(required = false) String totalHT,
                        @RequestParam(required = false) String totalTVA,
                        @RequestParam(required = false) String totalTTC,
                        @RequestParam(required = false) String currency,
                        @RequestParam(required = false) String chargeAccountNumber,
                        @RequestParam(required = false) String filePath,
                        @RequestParam(required = false) String qontoTxId,
                        @RequestParam(required = false) String notes,
                        HttpSession session,
                        RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        SupplierInvoice inv = new SupplierInvoice();
        inv.setTenant(tenant);
        inv.setVendorName(vendorName);
        inv.setInvoiceNumber(invoiceNumber);
        inv.setInvoiceDate(parseDate(invoiceDate));
        inv.setDueDate(parseDate(dueDate));
        inv.setTotalHT(parseDecimal(totalHT));
        inv.setTotalTVA(parseDecimal(totalTVA));
        inv.setTotalTTC(parseDecimal(totalTTC));
        inv.setCurrency(currency != null ? currency : "EUR");
        inv.setChargeAccountNumber(chargeAccountNumber);
        inv.setFilePath(filePath);
        inv.setOriginalFilename((String) session.getAttribute("mindeeOriginalFilename"));
        session.removeAttribute("mindeeOriginalFilename");
        inv.setQontoTransactionId(qontoTxId != null && !qontoTxId.isBlank() ? qontoTxId : null);
        inv.setNotes(notes);
        inv.setStatus(SupplierInvoice.Status.DRAFT);
        invoiceRepo.save(inv);
        ra.addFlashAttribute("successMessage", "Facture fournisseur enregistrée.");
        return "redirect:/intra/supplier-invoices/" + inv.getId();
    }

    // ── Détail ────────────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id,
                          @AuthenticationPrincipal CustomUserDetails principal,
                          Model model) {
        Tenant tenant = principal.getTenant();
        SupplierInvoice inv = invoiceRepo.findByIdAndTenant(id, tenant)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("invoice", inv);
        model.addAttribute("accounts", accountRepo.findByTenantAndActiveTrueOrderByNumberAsc(tenant));
        if (inv.getAccountingEntryId() != null) {
            entryRepo.findById(inv.getAccountingEntryId()).ifPresent(e -> model.addAttribute("acEntry", e));
        }
        if (inv.getPaymentEntryId() != null) {
            entryRepo.findById(inv.getPaymentEntryId()).ifPresent(e -> model.addAttribute("bqEntry", e));
        }

        // Transaction Qonto liée (si déjà rattachée)
        if (inv.getQontoTransactionId() != null) {
            try {
                Long qontoDbId = Long.parseLong(inv.getQontoTransactionId());
                qontoTxRepo.findByIdAndTenant(qontoDbId, tenant)
                        .ifPresent(tx -> model.addAttribute("linkedQontoTx", tx));
            } catch (NumberFormatException ignored) {}
        }

        // Candidates Qonto pour rapprochement (si pas encore PAID/CANCELLED)
        if (inv.getStatus() == SupplierInvoice.Status.DRAFT
                || inv.getStatus() == SupplierInvoice.Status.TO_PAY) {
            if (inv.getTotalTTC() != null) {
                BigDecimal ttc = inv.getTotalTTC();
                java.util.List<QontoTransaction> candidates = qontoTxRepo
                        .findTop200ByTenantAndReconciledFalseAndImportStatusOrderByImportedAtDesc(tenant, "IMPORTED")
                        .stream()
                        .filter(t -> "debit".equalsIgnoreCase(t.getSide()))
                        .sorted(java.util.Comparator.comparing(t -> t.getAmount() != null
                                ? t.getAmount().subtract(ttc).abs() : BigDecimal.valueOf(Long.MAX_VALUE)))
                        .limit(10).toList();
                model.addAttribute("qontoCandidates", candidates);
            }
        }

        return "intra/supplier-invoices/detail";
    }

    // ── Rapprochement Qonto ────────────────────────────────────────────────────

    @PostMapping("/{id}/reconcile/qonto/{txId}")
    @Transactional
    public String reconcileQonto(@PathVariable Long id,
                                  @PathVariable Long txId,
                                  @AuthenticationPrincipal CustomUserDetails principal,
                                  RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        SupplierInvoice inv = invoiceRepo.findByIdAndTenant(id, tenant)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        QontoTransaction tx = qontoTxRepo.findByIdAndTenant(txId, tenant)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        // Générer l'écriture AC si pas encore validée
        if (inv.getStatus() == SupplierInvoice.Status.DRAFT) {
            inv.setStatus(SupplierInvoice.Status.TO_PAY);
            accountingService.onInvoiceValidated(tenant, inv);
        }

        // Générer l'écriture BQ et passer en PAID
        inv.setStatus(SupplierInvoice.Status.PAID);
        accountingService.onInvoicePaid(tenant, inv);

        // Rattacher l'écriture BQ à la transaction Qonto et la marquer rapprochée
        String bqRef = "FURN-" + (inv.getInvoiceNumber() != null ? inv.getInvoiceNumber() : inv.getId()) + "-BQ";
        entryRepo.findByTenantAndReferenceStartingWithOrderByEntryDateAsc(tenant, bqRef)
                .stream().findFirst()
                .ifPresent(e -> tx.setAccountingEntryId(e.getId()));
        tx.setReconciled(true);
        qontoTxRepo.save(tx);

        // Mémoriser le lien sur la facture
        inv.setQontoTransactionId(String.valueOf(txId));
        invoiceRepo.save(inv);

        ra.addFlashAttribute("successMessage", "Transaction Qonto rapprochée — facture passée en Payée.");
        return "redirect:/intra/supplier-invoices/" + id;
    }

    // ── Changer le statut ─────────────────────────────────────────────────────

    @PostMapping("/{id}/validate")
    @Transactional
    public String validate(@PathVariable Long id,
                            @RequestParam(required = false) String chargeAccountNumber,
                            @AuthenticationPrincipal CustomUserDetails principal,
                            RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        invoiceRepo.findByIdAndTenant(id, tenant).ifPresent(inv -> {
            if (chargeAccountNumber != null && !chargeAccountNumber.isBlank()) {
                inv.setChargeAccountNumber(chargeAccountNumber);
            }
            inv.setStatus(SupplierInvoice.Status.TO_PAY);
            accountingService.onInvoiceValidated(tenant, inv);
            invoiceRepo.save(inv);
        });
        ra.addFlashAttribute("successMessage", "Facture validée — écriture AC générée.");
        return "redirect:/intra/supplier-invoices/" + id;
    }

    @PostMapping("/{id}/pay")
    @Transactional
    public String pay(@PathVariable Long id,
                       @AuthenticationPrincipal CustomUserDetails principal,
                       RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        invoiceRepo.findByIdAndTenant(id, tenant).ifPresent(inv -> {
            inv.setStatus(SupplierInvoice.Status.PAID);
            accountingService.onInvoicePaid(tenant, inv);
            invoiceRepo.save(inv);
        });
        ra.addFlashAttribute("successMessage", "Facture marquée payée — écriture BQ générée.");
        return "redirect:/intra/supplier-invoices/" + id;
    }

    @PostMapping("/{id}/cancel")
    @Transactional
    public String cancel(@PathVariable Long id,
                          @AuthenticationPrincipal CustomUserDetails principal,
                          RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        invoiceRepo.findByIdAndTenant(id, tenant).ifPresent(inv -> {
            inv.setStatus(SupplierInvoice.Status.CANCELLED);
            accountingService.onInvoiceCancelled(tenant, inv);
            invoiceRepo.save(inv);
        });
        ra.addFlashAttribute("successMessage", "Facture annulée — écriture d'extourne générée.");
        return "redirect:/intra/supplier-invoices/" + id;
    }

    // ── Import / prévisualisation factures Ingram ─────────────────────────────

    @GetMapping("/preview-ingram/{orderId}")
    public String previewIngram(@PathVariable Long orderId,
                                 @AuthenticationPrincipal CustomUserDetails principal,
                                 Model model, RedirectAttributes ra) {
        try {
            var order = ingramInvoiceService.previewInvoicesForOrder(orderId);
            model.addAttribute("orderId",   orderId);
            model.addAttribute("invoices",  order);
            model.addAttribute("existing",  invoiceRepo.findByOrderIdOrderByCreatedAtDesc(orderId));
            return "intra/supplier-invoices/ingram-preview";
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", "Erreur prévisualisation Ingram : " + e.getMessage());
            return "redirect:/intra/orders/" + orderId;
        }
    }

    @PostMapping("/import-ingram/{orderId}")
    public String importIngram(@PathVariable Long orderId,
                                @AuthenticationPrincipal CustomUserDetails principal,
                                RedirectAttributes ra) {
        try {
            int count = ingramInvoiceService.importInvoicesForOrder(orderId, principal.getTenant());
            if (count == 0) {
                ra.addFlashAttribute("warningMessage",
                        "Aucune nouvelle facture Ingram trouvée pour cette commande.");
            } else {
                ra.addFlashAttribute("successMessage",
                        count + " facture(s) Ingram importée(s) avec succès.");
            }
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage",
                    "Erreur lors de l'import des factures Ingram : " + e.getMessage());
        }
        return "redirect:/intra/orders/" + orderId;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDate.parse(s); } catch (Exception e) { return null; }
    }

    private BigDecimal parseDecimal(String s) {
        if (s == null || s.isBlank()) return null;
        try { return new BigDecimal(s.replace(",", ".")); } catch (Exception e) { return null; }
    }

    private String sanitize(String name) {
        if (name == null) return "file";
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
