package com.logicielpourtous.site.features.accounting;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.Tenant;
import com.stripe.exception.StripeException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

@Controller
@RequestMapping("/intra/accounting/stripe")
public class StripeImportController {

    private final StripeAccountingService stripeService;
    private final StripeAccountingMappingRepository mappingRepo;
    private final StripeTransactionRepository txRepo;
    private final AccountingAccountRepository accountRepo;
    private final AccountingEntryRepository entryRepo;
    private final AccountingEntryLineRepository lineRepo;
    private final AccountingJournalRepository journalRepo;
    private final AccountingService accountingService;

    public StripeImportController(StripeAccountingService stripeService,
                                   StripeAccountingMappingRepository mappingRepo,
                                   StripeTransactionRepository txRepo,
                                   AccountingAccountRepository accountRepo,
                                   AccountingEntryRepository entryRepo,
                                   AccountingEntryLineRepository lineRepo,
                                   AccountingJournalRepository journalRepo,
                                   AccountingService accountingService) {
        this.stripeService = stripeService;
        this.mappingRepo = mappingRepo;
        this.txRepo = txRepo;
        this.accountRepo = accountRepo;
        this.entryRepo = entryRepo;
        this.lineRepo = lineRepo;
        this.journalRepo = journalRepo;
        this.accountingService = accountingService;
    }

    // ── Paramétrage ──────────────────────────────────────────────────────────

    @GetMapping
    public String config(@AuthenticationPrincipal CustomUserDetails principal, Model model) {
        Tenant tenant = principal.getTenant();
        model.addAttribute("configured", stripeService.isConfigured());
        model.addAttribute("mappings", mappingRepo.findByTenantOrderByEventTypeAsc(tenant));
        model.addAttribute("accounts", accountRepo.findByTenantAndActiveTrueOrderByNumberAsc(tenant));
        return "intra/accounting/stripe/config";
    }

    @PostMapping("/mappings/save")
    @Transactional
    public String saveMapping(@RequestParam Long id,
                               @RequestParam String debitAccountNumber,
                               @RequestParam String creditAccountNumber,
                               @RequestParam(defaultValue = "false") boolean active,
                               RedirectAttributes ra) {
        mappingRepo.findById(id).ifPresent(m -> {
            m.setDebitAccountNumber(debitAccountNumber);
            m.setCreditAccountNumber(creditAccountNumber);
            m.setActive(active);
            mappingRepo.save(m);
        });
        ra.addFlashAttribute("successMessage", "Mapping mis à jour.");
        return "redirect:/intra/accounting/stripe";
    }

    // ── Liste des transactions ────────────────────────────────────────────────

    @GetMapping("/transactions")
    public String transactions(@AuthenticationPrincipal CustomUserDetails principal,
                                @RequestParam(defaultValue = "unreconciled") String filter,
                                Model model) {
        Tenant tenant = principal.getTenant();
        var transactions = switch (filter) {
            case "reconciled" -> txRepo.findTop200ByTenantAndReconciledTrueOrderByImportedAtDesc(tenant);
            case "all"        -> txRepo.findTop200ByTenantOrderByImportedAtDesc(tenant);
            default           -> txRepo.findTop200ByTenantAndReconciledFalseOrderByImportedAtDesc(tenant);
        };
        model.addAttribute("filter", filter);
        model.addAttribute("transactions", transactions);
        model.addAttribute("configured",        stripeService.isConfigured());
        model.addAttribute("countImported",      txRepo.countByTenantAndStripeStatus(tenant, "IMPORTED"));
        model.addAttribute("countSkipped",       txRepo.countByTenantAndStripeStatus(tenant, "SKIPPED"));
        model.addAttribute("countErrors",        txRepo.countByTenantAndStripeStatus(tenant, "ERROR"));
        model.addAttribute("countUnreconciled",  txRepo.countByTenantAndReconciledFalse(tenant));
        return "intra/accounting/stripe/transactions";
    }

    // ── Détail d'une transaction ──────────────────────────────────────────────

    @GetMapping("/transactions/{id}")
    public String transactionDetail(@PathVariable Long id,
                                     @AuthenticationPrincipal CustomUserDetails principal,
                                     Model model) {
        Tenant tenant = principal.getTenant();
        StripeTransaction tx = txRepo.findByIdAndTenant(id, tenant)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("tx", tx);
        model.addAttribute("accounts", accountRepo.findByTenantAndActiveTrueOrderByNumberAsc(tenant));
        if (tx.getAccountingEntryId() != null) {
            entryRepo.findById(tx.getAccountingEntryId())
                .ifPresent(e -> model.addAttribute("entry", e));
        }
        // Suggestion comptable depuis le mapping
        if (tx.getStripeEventType() != null) {
            mappingRepo.findByEventTypeAndTenantAndActiveTrue(tx.getStripeEventType(), tenant).ifPresent(m -> {
                model.addAttribute("suggestedDebitNumber", m.getDebitAccountNumber());
                model.addAttribute("suggestedCreditNumber", m.getCreditAccountNumber());
            });
        }
        return "intra/accounting/stripe/transaction-detail";
    }

    @PostMapping("/transactions/{id}/create-entry")
    @Transactional
    public String createEntry(@PathVariable Long id,
                               @RequestParam String debitAccountNumber,
                               @RequestParam String creditAccountNumber,
                               @RequestParam(required = false) String label,
                               @RequestParam(required = false) String partner,
                               @RequestParam(defaultValue = "false") boolean doReconcile,
                               @AuthenticationPrincipal CustomUserDetails principal,
                               RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        txRepo.findByIdAndTenant(id, tenant).ifPresent(tx -> {
            try {
                AccountingJournal journal = journalRepo.findByCodeAndTenant("STR", tenant)
                        .orElseGet(() -> journalRepo.findByCodeAndTenant("BQ", tenant)
                        .orElseThrow(() -> new IllegalStateException("Journal STR ou BQ introuvable")));
                FiscalYear year = accountingService.getCurrentFiscalYear(tenant);
                if (year == null) throw new IllegalStateException("Aucun exercice courant");

                AccountingAccount debitAcc = accountRepo.findByNumberAndTenant(debitAccountNumber.trim(), tenant)
                        .orElseThrow(() -> new IllegalStateException("Compte débit introuvable: " + debitAccountNumber));
                AccountingAccount creditAcc = accountRepo.findByNumberAndTenant(creditAccountNumber.trim(), tenant)
                        .orElseThrow(() -> new IllegalStateException("Compte crédit introuvable: " + creditAccountNumber));

                String lineLabel = (label != null && !label.isBlank()) ? label : (tx.getDescription() != null ? tx.getDescription() : "Transaction Stripe");
                String entryPartner = (partner != null && !partner.isBlank()) ? partner : tx.getDescription();
                LocalDate date = tx.getEventDate() != null ? tx.getEventDate() : LocalDate.now();
                BigDecimal amount = tx.getAmount() != null ? tx.getAmount() : BigDecimal.ZERO;

                AccountingEntry entry = new AccountingEntry();
                entry.setTenant(tenant);
                entry.setJournal(journal);
                entry.setFiscalYear(year);
                entry.setEntryDate(date);
                entry.setReference(accountingService.generateReference(journal, year));
                entry.setDescription(lineLabel);
                entry.setPartner(entryPartner);

                AccountingEntryLine dl = new AccountingEntryLine();
                dl.setAccount(debitAcc); dl.setLabel(lineLabel);
                dl.setDebit(amount); dl.setCredit(BigDecimal.ZERO); dl.setEntry(entry);
                entry.getLines().add(dl);

                AccountingEntryLine cl = new AccountingEntryLine();
                cl.setAccount(creditAcc); cl.setLabel(lineLabel);
                cl.setDebit(BigDecimal.ZERO); cl.setCredit(amount); cl.setEntry(entry);
                entry.getLines().add(cl);

                entryRepo.save(entry);
                tx.setAccountingEntryId(entry.getId());
                if (doReconcile) tx.setReconciled(true);
                txRepo.save(tx);
            } catch (Exception e) {
                ra.addFlashAttribute("errorMessage", "Erreur : " + e.getMessage());
            }
        });
        ra.addFlashAttribute("successMessage", "Écriture créée avec succès.");
        return "redirect:/intra/accounting/stripe/transactions/" + id;
    }

    @PostMapping("/transactions/{id}/reactivate")
    @Transactional
    public String reactivate(@PathVariable Long id,
                              @AuthenticationPrincipal CustomUserDetails principal,
                              RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        txRepo.findByIdAndTenant(id, tenant).ifPresent(tx -> {
            tx.setStripeStatus("IMPORTED");
            tx.setErrorMessage(null);
            txRepo.save(tx);
        });
        ra.addFlashAttribute("successMessage", "Transaction marquée comme importée — vous pouvez maintenant créer une écriture.");
        return "redirect:/intra/accounting/stripe/transactions/" + id;
    }

    @PostMapping("/transactions/{id}/detach")
    @Transactional
    public String detach(@PathVariable Long id,
                         @AuthenticationPrincipal CustomUserDetails principal,
                         RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        txRepo.findByIdAndTenant(id, tenant).ifPresent(tx -> {
            tx.setAccountingEntryId(null);
            tx.setReconciled(false);
            txRepo.save(tx);
        });
        ra.addFlashAttribute("successMessage", "Écriture détachée — la transaction est à nouveau à rapprocher.");
        return "redirect:/intra/accounting/stripe/transactions/" + id;
    }

    // ── Backfill bank account number on legacy transactions ───────────────────

    @PostMapping("/transactions/backfill-bank-account")
    @Transactional
    public String backfillBankAccount(@AuthenticationPrincipal CustomUserDetails principal,
                                       RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        java.util.List<StripeTransaction> needsBackfill = txRepo.findNeedingBankAccountBackfill(tenant);
        for (StripeTransaction tx : needsBackfill) {
            entryRepo.findById(tx.getAccountingEntryId()).ifPresent(entry -> {
                entry.getLines().stream()
                        .filter(l -> l.getAccount() != null && l.getAccount().getNumber() != null
                                && (l.getAccount().getNumber().startsWith("51") || l.getAccount().getNumber().startsWith("53")))
                        .map(l -> l.getAccount().getNumber())
                        .findFirst()
                        .ifPresent(tx::setBankAccountNumber);
            });
            txRepo.save(tx);
        }
        ra.addFlashAttribute("successMessage", "Backfill terminé : " + needsBackfill.size() + " transaction(s) mises à jour.");
        return "redirect:/intra/accounting/stripe/transactions";
    }

    // ── Rapprochement ─────────────────────────────────────────────────────────

    @PostMapping("/transactions/{id}/reconcile")
    @Transactional
    public String reconcile(@PathVariable Long id,
                             @AuthenticationPrincipal CustomUserDetails principal,
                             @RequestParam(defaultValue = "unreconciled") String filter,
                             @RequestParam(required = false) String redirectTo,
                             RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        txRepo.findByIdAndTenant(id, tenant).ifPresent(tx -> {
            tx.setReconciled(!tx.isReconciled());
            txRepo.save(tx);
        });
        if (redirectTo != null && !redirectTo.isBlank()) return "redirect:" + redirectTo;
        return "redirect:/intra/accounting/stripe/transactions?filter=" + filter;
    }

    @PostMapping("/transactions/{id}/reconcile-detail")
    @Transactional
    public String reconcileFromDetail(@PathVariable Long id,
                                       @AuthenticationPrincipal CustomUserDetails principal) {
        Tenant tenant = principal.getTenant();
        txRepo.findByIdAndTenant(id, tenant).ifPresent(tx -> {
            tx.setReconciled(!tx.isReconciled());
            txRepo.save(tx);
        });
        return "redirect:/intra/accounting/stripe/transactions/" + id;
    }

    // ── Re-affectation des comptes ────────────────────────────────────────────

    @PostMapping("/transactions/{id}/reaffect")
    @Transactional
    public String reaffect(@PathVariable Long id,
                            @RequestParam Map<String, String> params,
                            @RequestParam(defaultValue = "false") boolean doReconcile,
                            @AuthenticationPrincipal CustomUserDetails principal,
                            RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        txRepo.findByIdAndTenant(id, tenant).ifPresent(tx -> {
            if (tx.getAccountingEntryId() != null) {
                params.entrySet().stream()
                    .filter(e -> e.getKey().startsWith("line_"))
                    .forEach(e -> {
                        try {
                            Long lineId = Long.parseLong(e.getKey().substring(5));
                            lineRepo.findById(lineId).ifPresent(line -> {
                                if (line.getEntry().getTenant().getSchemaName().equals(tenant.getSchemaName())) {
                                    accountRepo.findByNumberAndTenant(e.getValue(), tenant)
                                        .ifPresent(line::setAccount);
                                    lineRepo.save(line);
                                }
                            });
                        } catch (NumberFormatException ignored) {}
                    });
            }
            if (doReconcile) {
                tx.setReconciled(true);
                txRepo.save(tx);
            }
        });
        ra.addFlashAttribute("successMessage", doReconcile
            ? "Affectation mise à jour et transaction rapprochée."
            : "Affectation comptable mise à jour.");
        return "redirect:/intra/accounting/stripe/transactions/" + id;
    }

    // ── Synchronisation ───────────────────────────────────────────────────────

    @PostMapping("/sync")
    public String sync(@AuthenticationPrincipal CustomUserDetails principal,
                       @RequestParam(defaultValue = "30") String days,
                       RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        try {
            StripeAccountingService.SyncResult result = stripeService.syncFromStripe(tenant, parseDays(days));
            ra.addFlashAttribute("successMessage",
                    "Sync terminé — " + result.imported() + " importé(s), " +
                    result.skipped() + " ignoré(s), " + result.errors() + " erreur(s).");
        } catch (StripeException e) {
            ra.addFlashAttribute("errorMessage", "Erreur Stripe : " + e.getMessage());
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/intra/accounting/stripe/transactions";
    }

    private static int parseDays(String days) {
        if ("ytd".equals(days)) {
            java.time.LocalDate jan1 = java.time.LocalDate.of(java.time.LocalDate.now().getYear(), 1, 1);
            return (int) java.time.temporal.ChronoUnit.DAYS.between(jan1, java.time.LocalDate.now()) + 1;
        }
        return Integer.parseInt(days);
    }
}
