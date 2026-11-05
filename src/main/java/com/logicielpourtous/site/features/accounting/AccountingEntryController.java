package com.logicielpourtous.site.features.accounting;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/intra/accounting/entries")
public class AccountingEntryController {

    private final AccountingEntryRepository entryRepo;
    private final AccountingJournalRepository journalRepo;
    private final FiscalYearRepository yearRepo;
    private final AccountingAccountRepository accountRepo;
    private final AccountingService service;
    private final AccountingSetupService setupService;
    private final ObjectMapper objectMapper;

    public AccountingEntryController(AccountingEntryRepository entryRepo,
                                     AccountingJournalRepository journalRepo,
                                     FiscalYearRepository yearRepo,
                                     AccountingAccountRepository accountRepo,
                                     AccountingService service,
                                     AccountingSetupService setupService,
                                     ObjectMapper objectMapper) {
        this.entryRepo = entryRepo;
        this.journalRepo = journalRepo;
        this.yearRepo = yearRepo;
        this.accountRepo = accountRepo;
        this.service = service;
        this.setupService = setupService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public String list(@AuthenticationPrincipal CustomUserDetails principal,
                       @RequestParam(required = false) Long journalId,
                       @RequestParam(required = false) Long yearId,
                       Model model) {
        Tenant tenant = principal.getTenant();
        if (!setupService.isInitialized(tenant)) setupService.initializeForTenant(tenant);
        FiscalYear year = yearId != null
                ? yearRepo.findById(yearId).orElse(service.getCurrentFiscalYear(tenant))
                : service.getCurrentFiscalYear(tenant);
        model.addAttribute("currentYear", year);
        model.addAttribute("fiscalYears", yearRepo.findByTenantOrderByStartDateDesc(tenant));
        model.addAttribute("journals", journalRepo.findByTenantOrderByCodeAsc(tenant));

        if (year != null) {
            if (journalId != null) {
                journalRepo.findById(journalId).ifPresent(j -> {
                    model.addAttribute("selectedJournal", j);
                    model.addAttribute("entries", entryRepo.findByTenantAndJournalAndFiscalYearOrderByEntryDateAscIdAsc(tenant, j, year));
                });
            } else {
                model.addAttribute("entries", entryRepo.findByTenantAndFiscalYearOrderByEntryDateAscIdAsc(tenant, year));
            }
        }
        return "intra/accounting/entries/list";
    }

    @GetMapping("/new")
    public String newForm(@AuthenticationPrincipal CustomUserDetails principal, Model model) {
        Tenant tenant = principal.getTenant();
        model.addAttribute("journals", journalRepo.findByTenantAndActiveTrueOrderByCodeAsc(tenant));
        model.addAttribute("accounts", accountRepo.findByTenantAndActiveTrueOrderByNumberAsc(tenant));
        model.addAttribute("fiscalYears", yearRepo.findByTenantOrderByStartDateDesc(tenant));
        model.addAttribute("currentYear", service.getCurrentFiscalYear(tenant));
        return "intra/accounting/entries/form";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        AccountingEntry entry = entryRepo.findById(id).orElseThrow();
        entry.getLines().size();
        model.addAttribute("entry", entry);
        return "intra/accounting/entries/detail";
    }

    @PostMapping("/save")
    @Transactional
    public String save(@AuthenticationPrincipal CustomUserDetails principal,
                       @RequestParam Long journalId,
                       @RequestParam Long fiscalYearId,
                       @RequestParam String entryDate,
                       @RequestParam(required = false) String description,
                       @RequestParam String linesJson,
                       RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        AccountingJournal journal = journalRepo.findById(journalId).orElseThrow();
        FiscalYear year = yearRepo.findById(fiscalYearId).orElseThrow();

        if (year.isClosed()) {
            ra.addFlashAttribute("errorMessage", "L'exercice " + year.getLabel() + " est clôturé.");
            return "redirect:/intra/accounting/entries/new";
        }

        List<Map<String, Object>> lineData;
        try {
            lineData = objectMapper.readValue(linesJson, new TypeReference<>() {});
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", "Format des lignes invalide.");
            return "redirect:/intra/accounting/entries/new";
        }

        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        for (Map<String, Object> ld : lineData) {
            totalDebit  = totalDebit.add(new BigDecimal(ld.getOrDefault("debit",  "0").toString()));
            totalCredit = totalCredit.add(new BigDecimal(ld.getOrDefault("credit", "0").toString()));
        }
        if (totalDebit.compareTo(totalCredit) != 0) {
            ra.addFlashAttribute("errorMessage", "L'écriture n'est pas équilibrée (Δ = " + totalDebit.subtract(totalCredit).toPlainString() + ").");
            return "redirect:/intra/accounting/entries/new";
        }

        AccountingEntry entry = new AccountingEntry();
        entry.setTenant(tenant);
        entry.setJournal(journal);
        entry.setFiscalYear(year);
        entry.setEntryDate(LocalDate.parse(entryDate));
        entry.setDescription(description);
        entry.setReference(service.generateReference(journal, year));

        for (Map<String, Object> ld : lineData) {
            Long accountId = Long.parseLong(ld.get("accountId").toString());
            AccountingEntryLine line = new AccountingEntryLine();
            line.setAccount(accountRepo.findById(accountId).orElseThrow());
            line.setLabel(ld.getOrDefault("label", "").toString());
            line.setDebit(new BigDecimal(ld.getOrDefault("debit",  "0").toString()));
            line.setCredit(new BigDecimal(ld.getOrDefault("credit", "0").toString()));
            line.setEntry(entry);
            entry.getLines().add(line);
        }

        entryRepo.save(entry);
        ra.addFlashAttribute("successMessage", "Écriture enregistrée : " + entry.getReference());
        return "redirect:/intra/accounting/entries";
    }

    @PostMapping("/{id}/delete")
    @Transactional
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        entryRepo.findById(id).ifPresent(e -> {
            if (!e.isLocked()) entryRepo.delete(e);
        });
        ra.addFlashAttribute("successMessage", "Écriture supprimée.");
        return "redirect:/intra/accounting/entries";
    }

    @PostMapping("/{id}/lock")
    @Transactional
    public String lock(@PathVariable Long id, RedirectAttributes ra) {
        entryRepo.findById(id).ifPresent(e -> { e.setLocked(true); entryRepo.save(e); });
        ra.addFlashAttribute("successMessage", "Écriture verrouillée.");
        return "redirect:/intra/accounting/entries/" + id;
    }
}
