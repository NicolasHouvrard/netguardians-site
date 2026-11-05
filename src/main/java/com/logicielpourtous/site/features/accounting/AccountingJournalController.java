package com.logicielpourtous.site.features.accounting;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.Tenant;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/intra/accounting/journals")
public class AccountingJournalController {

    private final AccountingJournalRepository repo;
    private final AccountingSetupService setupService;

    public AccountingJournalController(AccountingJournalRepository repo, AccountingSetupService setupService) {
        this.repo = repo;
        this.setupService = setupService;
    }

    @GetMapping
    public String list(@AuthenticationPrincipal CustomUserDetails principal, Model model) {
        Tenant tenant = principal.getTenant();
        if (!setupService.isInitialized(tenant)) setupService.initializeForTenant(tenant);
        model.addAttribute("journals", repo.findByTenantOrderByCodeAsc(tenant));
        model.addAttribute("journalTypes", JournalType.values());
        model.addAttribute("newJournal", new AccountingJournal());
        return "intra/accounting/journals/list";
    }

    @PostMapping("/save")
    public String save(@AuthenticationPrincipal CustomUserDetails principal,
                       @ModelAttribute AccountingJournal journal, RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        if (journal.getId() == null && repo.existsByCodeAndTenant(journal.getCode(), tenant)) {
            ra.addFlashAttribute("errorMessage", "Le journal " + journal.getCode() + " existe déjà.");
            return "redirect:/intra/accounting/journals";
        }
        journal.setTenant(tenant);
        repo.save(journal);
        ra.addFlashAttribute("successMessage", "Journal " + journal.getCode() + " sauvegardé.");
        return "redirect:/intra/accounting/journals";
    }
}
