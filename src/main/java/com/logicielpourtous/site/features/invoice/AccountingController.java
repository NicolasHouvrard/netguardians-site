package com.logicielpourtous.site.features.invoice;

import com.logicielpourtous.site.features.accounting.AccountingAccount;
import com.logicielpourtous.site.features.accounting.AccountingAccountRepository;
import com.logicielpourtous.site.features.accounting.AccountingBatch;
import com.logicielpourtous.site.features.accounting.AccountingBatchRepository;
import com.logicielpourtous.site.features.accounting.AccountingEntryLineRepository;
import com.logicielpourtous.site.features.accounting.QontoTransaction;
import com.logicielpourtous.site.features.accounting.QontoTransactionRepository;
import com.logicielpourtous.site.features.accounting.StripeTransaction;
import com.logicielpourtous.site.features.accounting.StripeTransactionRepository;
import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.purchase.SupplierInvoice;
import com.logicielpourtous.site.features.purchase.SupplierInvoiceRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/intra/accounting")
public class AccountingController {

    private final InvoiceRepository invoiceRepository;
    private final SupplierInvoiceRepository supplierInvoiceRepo;
    private final AccountingAccountRepository accountRepo;
    private final AccountingEntryLineRepository lineRepo;
    private final QontoTransactionRepository qontoTxRepo;
    private final StripeTransactionRepository stripeTxRepo;
    private final AccountingBatchRepository batchRepo;

    public AccountingController(InvoiceRepository invoiceRepository,
                                SupplierInvoiceRepository supplierInvoiceRepo,
                                AccountingAccountRepository accountRepo,
                                AccountingEntryLineRepository lineRepo,
                                QontoTransactionRepository qontoTxRepo,
                                StripeTransactionRepository stripeTxRepo,
                                AccountingBatchRepository batchRepo) {
        this.invoiceRepository = invoiceRepository;
        this.supplierInvoiceRepo = supplierInvoiceRepo;
        this.accountRepo = accountRepo;
        this.lineRepo = lineRepo;
        this.qontoTxRepo = qontoTxRepo;
        this.stripeTxRepo = stripeTxRepo;
        this.batchRepo = batchRepo;
    }

    @GetMapping({"", "/"})
    public String dashboard(@AuthenticationPrincipal CustomUserDetails principal, Model model) {
        // ── Factures clients ─────────────────────────────────────────────────
        model.addAttribute("cliDraft",   invoiceRepository.countByStatus(Invoice.InvoiceStatus.DRAFT));
        model.addAttribute("cliSent",    invoiceRepository.countByStatus(Invoice.InvoiceStatus.SENT));
        model.addAttribute("cliPaid",    invoiceRepository.countByStatus(Invoice.InvoiceStatus.PAID));
        model.addAttribute("cliOverdue", invoiceRepository.countByStatus(Invoice.InvoiceStatus.OVERDUE));
        model.addAttribute("cliRevenue", invoiceRepository.sumPaidSales());
        model.addAttribute("cliUnpaid",  invoiceRepository.sumOverdue());

        if (principal != null) {
            Tenant tenant = principal.getTenant();

            // ── Factures fournisseurs ─────────────────────────────────────────
            model.addAttribute("suppDraft",  supplierInvoiceRepo.countByTenantAndStatus(tenant, SupplierInvoice.Status.DRAFT));
            model.addAttribute("suppToPay",  supplierInvoiceRepo.countByTenantAndStatus(tenant, SupplierInvoice.Status.TO_PAY));
            model.addAttribute("suppPaid",   supplierInvoiceRepo.countByTenantAndStatus(tenant, SupplierInvoice.Status.PAID));
            model.addAttribute("suppSpend",  supplierInvoiceRepo.sumTotalTTCByTenantAndStatus(tenant, SupplierInvoice.Status.PAID));
            model.addAttribute("suppToPay$", supplierInvoiceRepo.sumTotalTTCByTenantAndStatus(tenant, SupplierInvoice.Status.TO_PAY));

            // ── Comptes de trésorerie ─────────────────────────────────────────
            List<AccountingAccount> bankAccounts = accountRepo.findByTenantAndActiveTrueOrderByNumberAsc(tenant).stream()
                    .filter(a -> a.getNumber() != null &&
                            (a.getNumber().startsWith("51") || a.getNumber().startsWith("53")))
                    .toList();
            List<Map<String, Object>> bankWidgets = new ArrayList<>();
            for (AccountingAccount acc : bankAccounts) {
                long unreconciled = qontoTxRepo.countByTenantAndBankAccountNumberAndReconciledFalseAndImportStatus(tenant, acc.getNumber(), "IMPORTED")
                        + stripeTxRepo.countByTenantAndBankAccountNumberAndReconciledFalseAndStripeStatus(tenant, acc.getNumber(), "IMPORTED");
                java.math.BigDecimal balance = lineRepo.getBalanceByAccountNumberAndTenant(acc.getNumber(), tenant);
                Map<String, Object> w = new LinkedHashMap<>();
                w.put("number", acc.getNumber());
                w.put("label", acc.getLabel());
                w.put("unreconciled", unreconciled);
                w.put("balance", balance != null ? balance : java.math.BigDecimal.ZERO);
                bankWidgets.add(w);
            }
            model.addAttribute("bankWidgets", bankWidgets);

            // ── Lots OD ───────────────────────────────────────────────────────
            List<AccountingBatch> allBatches = batchRepo.findByTenantOrderByCreatedAtDesc(tenant);
            List<AccountingBatch> draftBatches = allBatches.stream().filter(AccountingBatch::isDraft).toList();
            model.addAttribute("draftBatches", draftBatches);
            model.addAttribute("countDraftOD", draftBatches.size());
        }

        return "intra/accounting/dashboard";
    }

    @GetMapping("/fiscal")
    public String fiscalDashboard(@AuthenticationPrincipal CustomUserDetails principal, Model model) {
        return "intra/accounting/fiscal-dashboard";
    }

    @GetMapping("/invoices")
    public String invoices(Model model) {
        model.addAttribute("invoices", invoiceRepository.findAll());
        return "intra/accounting/invoices";
    }

    @PostMapping("/invoices/{id}/status")
    public String updateStatus(@PathVariable Long id,
                               @RequestParam Invoice.InvoiceStatus status,
                               RedirectAttributes ra) {
        invoiceRepository.findById(id).ifPresent(inv -> {
            inv.setStatus(status);
            invoiceRepository.save(inv);
        });
        ra.addFlashAttribute("successMessage", "Statut mis à jour.");
        return "redirect:/intra/accounting/invoices";
    }

    // ── Bank transactions unified view ────────────────────────────────────────

    @GetMapping("/bank-transactions")
    public String bankTransactions(@AuthenticationPrincipal CustomUserDetails principal,
                                   @RequestParam(required = false) String account,
                                   @RequestParam(defaultValue = "unreconciled") String filter,
                                   Model model) {
        if (account == null || account.isBlank()) return "redirect:/intra/accounting";

        Tenant tenant = principal.getTenant();

        AccountingAccount acc = accountRepo.findByNumberAndTenant(account, tenant).orElse(null);
        if (acc == null) return "redirect:/intra/accounting";

        List<QontoTransaction> qontoTxs;
        List<StripeTransaction> stripeTxs;

        switch (filter) {
            case "reconciled" -> {
                qontoTxs  = qontoTxRepo.findTop200ByTenantAndBankAccountNumberAndReconciledTrueOrderByImportedAtDesc(tenant, account);
                stripeTxs = stripeTxRepo.findTop200ByTenantAndBankAccountNumberAndReconciledTrueOrderByImportedAtDesc(tenant, account);
            }
            case "all" -> {
                qontoTxs  = qontoTxRepo.findTop200ByTenantAndBankAccountNumberOrderByImportedAtDesc(tenant, account);
                stripeTxs = stripeTxRepo.findTop200ByTenantAndBankAccountNumberOrderByImportedAtDesc(tenant, account);
            }
            default -> {
                qontoTxs  = qontoTxRepo.findTop200ByTenantAndBankAccountNumberAndReconciledFalseAndImportStatusOrderByImportedAtDesc(tenant, account, "IMPORTED");
                stripeTxs = stripeTxRepo.findTop200ByTenantAndBankAccountNumberAndReconciledFalseAndStripeStatusOrderByImportedAtDesc(tenant, account, "IMPORTED");
            }
        }

        long totalImported = qontoTxRepo.countByTenantAndBankAccountNumberAndImportStatus(tenant, account, "IMPORTED")
                + stripeTxRepo.countByTenantAndBankAccountNumberAndStripeStatus(tenant, account, "IMPORTED");
        long totalUnreconciled = qontoTxRepo.countByTenantAndBankAccountNumberAndReconciledFalseAndImportStatus(tenant, account, "IMPORTED")
                + stripeTxRepo.countByTenantAndBankAccountNumberAndReconciledFalseAndStripeStatus(tenant, account, "IMPORTED");

        model.addAttribute("acc", acc);
        model.addAttribute("filter", filter);
        model.addAttribute("qontoTxs", qontoTxs);
        model.addAttribute("stripeTxs", stripeTxs);
        model.addAttribute("totalImported", totalImported);
        model.addAttribute("totalUnreconciled", totalUnreconciled);
        return "intra/accounting/bank-transactions";
    }
}
