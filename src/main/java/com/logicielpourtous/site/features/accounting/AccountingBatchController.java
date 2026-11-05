package com.logicielpourtous.site.features.accounting;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.Tenant;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Controller
@RequestMapping("/intra/accounting/batches")
public class AccountingBatchController {

    private final AccountingBatchRepository batchRepo;
    private final AccountingEntryRepository entryRepo;
    private final AccountingAccountRepository accountRepo;
    private final AccountingJournalRepository journalRepo;
    private final AccountingService accountingService;

    public AccountingBatchController(AccountingBatchRepository batchRepo,
                                      AccountingEntryRepository entryRepo,
                                      AccountingAccountRepository accountRepo,
                                      AccountingJournalRepository journalRepo,
                                      AccountingService accountingService) {
        this.batchRepo = batchRepo;
        this.entryRepo = entryRepo;
        this.accountRepo = accountRepo;
        this.journalRepo = journalRepo;
        this.accountingService = accountingService;
    }

    // ── Liste ─────────────────────────────────────────────────────────────────

    @GetMapping
    public String list(@AuthenticationPrincipal CustomUserDetails principal, Model model) {
        Tenant tenant = principal.getTenant();
        List<AccountingBatch> batches = batchRepo.findByTenantOrderByCreatedAtDesc(tenant);

        // Enrichir avec nb écritures par lot
        List<BatchSummary> summaries = new ArrayList<>();
        for (AccountingBatch b : batches) {
            long count = entryRepo.countByBatchId(b.getId());
            summaries.add(new BatchSummary(b, count));
        }

        model.addAttribute("summaries", summaries);
        model.addAttribute("journals", journalRepo.findByTenantOrderByCodeAsc(tenant));
        return "intra/accounting/batches/list";
    }

    // ── Création ──────────────────────────────────────────────────────────────

    @PostMapping("/save")
    @Transactional
    public String save(@AuthenticationPrincipal CustomUserDetails principal,
                       @RequestParam(required = false) Long batchId,
                       @RequestParam String name,
                       @RequestParam(required = false) String description,
                       @RequestParam String batchDate,
                       @RequestParam(defaultValue = "OD") String journalCode,
                       RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        AccountingBatch batch;
        if (batchId != null) {
            batch = batchRepo.findByIdAndTenant(batchId, tenant)
                    .orElseThrow(() -> new IllegalArgumentException("Lot introuvable"));
            if (!batch.isDraft()) {
                ra.addFlashAttribute("errorMessage", "Impossible de modifier un lot validé.");
                return "redirect:/intra/accounting/batches/" + batchId;
            }
        } else {
            batch = new AccountingBatch();
            batch.setTenant(tenant);
        }
        batch.setName(name);
        batch.setDescription(description);
        batch.setBatchDate(LocalDate.parse(batchDate));
        batch.setJournalCode(journalCode);
        batch = batchRepo.save(batch);
        ra.addFlashAttribute("successMessage", "Lot sauvegardé.");
        return "redirect:/intra/accounting/batches/" + batch.getId();
    }

    // ── Détail ────────────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id,
                          @AuthenticationPrincipal CustomUserDetails principal,
                          Model model) {
        Tenant tenant = principal.getTenant();
        AccountingBatch batch = batchRepo.findByIdAndTenant(id, tenant)
                .orElse(null);
        if (batch == null) return "redirect:/intra/accounting/batches";

        List<AccountingEntry> entries = entryRepo.findByBatchIdOrderByIdAsc(id);

        BigDecimal totalDebit  = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        for (AccountingEntry e : entries) {
            for (AccountingEntryLine l : e.getLines()) {
                if (l.getDebit()  != null) totalDebit  = totalDebit.add(l.getDebit());
                if (l.getCredit() != null) totalCredit = totalCredit.add(l.getCredit());
            }
        }
        boolean balanced = totalDebit.compareTo(totalCredit) == 0;

        model.addAttribute("batch",       batch);
        model.addAttribute("entries",     entries);
        model.addAttribute("totalDebit",  totalDebit);
        model.addAttribute("totalCredit", totalCredit);
        model.addAttribute("balanced",    balanced);
        model.addAttribute("accounts",    accountRepo.findByTenantAndActiveTrueOrderByNumberAsc(tenant));
        model.addAttribute("journals",    journalRepo.findByTenantOrderByCodeAsc(tenant));
        return "intra/accounting/batches/detail";
    }

    // ── Ajouter une écriture ──────────────────────────────────────────────────

    @PostMapping("/{id}/add-entry")
    @Transactional
    public String addEntry(@PathVariable Long id,
                            @AuthenticationPrincipal CustomUserDetails principal,
                            @RequestParam String entryDescription,
                            @RequestParam(required = false) String partner,
                            @RequestParam List<String> accountNumber,
                            @RequestParam List<String> lineLabel,
                            @RequestParam List<String> debit,
                            @RequestParam List<String> credit,
                            RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        AccountingBatch batch = batchRepo.findByIdAndTenant(id, tenant)
                .orElse(null);
        if (batch == null || !batch.isDraft()) {
            ra.addFlashAttribute("errorMessage", "Lot introuvable ou déjà validé.");
            return "redirect:/intra/accounting/batches";
        }

        AccountingJournal journal = journalRepo.findByCodeAndTenant(batch.getJournalCode(), tenant)
                .orElseThrow(() -> new IllegalStateException("Journal « " + batch.getJournalCode() + " » introuvable — créez-le d'abord."));

        FiscalYear year = accountingService.getCurrentFiscalYear(tenant);
        if (year == null)    throw new IllegalStateException("Aucun exercice comptable courant.");
        if (year.isClosed()) throw new IllegalStateException("L'exercice courant est clôturé.");

        AccountingEntry entry = new AccountingEntry();
        entry.setTenant(tenant);
        entry.setJournal(journal);
        entry.setFiscalYear(year);
        entry.setEntryDate(batch.getBatchDate());
        entry.setReference(accountingService.generateReference(journal, year));
        entry.setDescription(entryDescription);
        entry.setPartner(partner != null && !partner.isBlank() ? partner : null);
        entry.setBatchId(id);

        BigDecimal sumD = BigDecimal.ZERO, sumC = BigDecimal.ZERO;
        for (int i = 0; i < accountNumber.size(); i++) {
            String num = accountNumber.get(i);
            if (num == null || num.isBlank()) continue;
            BigDecimal d = parseBD(debit.get(i));
            BigDecimal c = parseBD(credit.get(i));
            if (d.compareTo(BigDecimal.ZERO) == 0 && c.compareTo(BigDecimal.ZERO) == 0) continue;

            AccountingAccount acc = accountRepo.findByNumberAndTenant(num.trim(), tenant)
                    .orElseThrow(() -> new IllegalStateException("Compte introuvable : " + num));

            AccountingEntryLine line = new AccountingEntryLine();
            line.setEntry(entry);
            line.setAccount(acc);
            line.setLabel(lineLabel.get(i).isBlank() ? entryDescription : lineLabel.get(i));
            line.setDebit(d);
            line.setCredit(c);
            entry.getLines().add(line);
            sumD = sumD.add(d);
            sumC = sumC.add(c);
        }

        if (entry.getLines().isEmpty()) {
            ra.addFlashAttribute("errorMessage", "L'écriture ne contient aucune ligne valide.");
            return "redirect:/intra/accounting/batches/" + id;
        }
        if (sumD.compareTo(sumC) != 0) {
            ra.addFlashAttribute("errorMessage",
                    "Écriture non équilibrée — débit " + sumD + " ≠ crédit " + sumC);
            return "redirect:/intra/accounting/batches/" + id;
        }

        entryRepo.save(entry);
        ra.addFlashAttribute("successMessage", "Écriture " + entry.getReference() + " ajoutée.");
        return "redirect:/intra/accounting/batches/" + id;
    }

    // ── Supprimer une écriture ────────────────────────────────────────────────

    @PostMapping("/{id}/delete-entry/{entryId}")
    @Transactional
    public String deleteEntry(@PathVariable Long id,
                               @PathVariable Long entryId,
                               @AuthenticationPrincipal CustomUserDetails principal,
                               RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        AccountingBatch batch = batchRepo.findByIdAndTenant(id, tenant).orElse(null);
        if (batch == null || !batch.isDraft()) {
            ra.addFlashAttribute("errorMessage", "Opération impossible sur ce lot.");
            return "redirect:/intra/accounting/batches/" + id;
        }
        entryRepo.findById(entryId).ifPresent(e -> {
            if (id.equals(e.getBatchId())) entryRepo.delete(e);
        });
        ra.addFlashAttribute("successMessage", "Écriture supprimée.");
        return "redirect:/intra/accounting/batches/" + id;
    }

    // ── Valider le lot ────────────────────────────────────────────────────────

    @PostMapping("/{id}/post")
    @Transactional
    public String post(@PathVariable Long id,
                        @AuthenticationPrincipal CustomUserDetails principal,
                        RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        AccountingBatch batch = batchRepo.findByIdAndTenant(id, tenant).orElse(null);
        if (batch == null || !batch.isDraft()) {
            ra.addFlashAttribute("errorMessage", "Lot introuvable ou déjà validé.");
            return "redirect:/intra/accounting/batches";
        }

        List<AccountingEntry> entries = entryRepo.findByBatchIdOrderByIdAsc(id);
        if (entries.isEmpty()) {
            ra.addFlashAttribute("errorMessage", "Le lot est vide — ajoutez au moins une écriture.");
            return "redirect:/intra/accounting/batches/" + id;
        }

        BigDecimal totalDebit  = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        for (AccountingEntry e : entries) {
            for (AccountingEntryLine l : e.getLines()) {
                if (l.getDebit()  != null) totalDebit  = totalDebit.add(l.getDebit());
                if (l.getCredit() != null) totalCredit = totalCredit.add(l.getCredit());
            }
        }
        if (totalDebit.compareTo(totalCredit) != 0) {
            ra.addFlashAttribute("errorMessage",
                    "Lot non équilibré (débit " + totalDebit + " ≠ crédit " + totalCredit + ") — impossible de valider.");
            return "redirect:/intra/accounting/batches/" + id;
        }

        entries.forEach(e -> { e.setLocked(true); entryRepo.save(e); });
        batch.setStatus("POSTED");
        batchRepo.save(batch);
        ra.addFlashAttribute("successMessage", "Lot validé — " + entries.size() + " écriture(s) verrouillée(s).");
        return "redirect:/intra/accounting/batches/" + id;
    }

    // ── Supprimer le lot ──────────────────────────────────────────────────────

    @PostMapping("/{id}/delete")
    @Transactional
    public String delete(@PathVariable Long id,
                          @AuthenticationPrincipal CustomUserDetails principal,
                          RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        AccountingBatch batch = batchRepo.findByIdAndTenant(id, tenant).orElse(null);
        if (batch == null) return "redirect:/intra/accounting/batches";
        if (!batch.isDraft()) {
            ra.addFlashAttribute("errorMessage", "Impossible de supprimer un lot validé.");
            return "redirect:/intra/accounting/batches/" + id;
        }
        entryRepo.findByBatchIdOrderByIdAsc(id).forEach(entryRepo::delete);
        batchRepo.delete(batch);
        ra.addFlashAttribute("successMessage", "Lot supprimé.");
        return "redirect:/intra/accounting/batches";
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static BigDecimal parseBD(String s) {
        if (s == null || s.isBlank()) return BigDecimal.ZERO;
        try { return new BigDecimal(s.replace(",", ".")); }
        catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    public record BatchSummary(AccountingBatch batch, long entryCount) {}
}
