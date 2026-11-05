package com.logicielpourtous.site.features.accounting;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.Tenant;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/intra/accounting/setup")
public class AccountingSetupController {

    private final AccountingSetupService setupService;
    private final AccountingAccountRepository accountRepo;
    private final AccountingJournalRepository journalRepo;
    private final FiscalYearRepository fiscalYearRepo;

    public AccountingSetupController(AccountingSetupService setupService,
                                      AccountingAccountRepository accountRepo,
                                      AccountingJournalRepository journalRepo,
                                      FiscalYearRepository fiscalYearRepo) {
        this.setupService = setupService;
        this.accountRepo = accountRepo;
        this.journalRepo = journalRepo;
        this.fiscalYearRepo = fiscalYearRepo;
    }

    @GetMapping
    public String dashboard(@AuthenticationPrincipal CustomUserDetails principal, Model model) {
        Tenant tenant = principal.getTenant();
        model.addAttribute("initialized", setupService.isInitialized(tenant));
        model.addAttribute("accountCount", accountRepo.findByTenantOrderByNumberAsc(tenant).size());
        model.addAttribute("journalCount", journalRepo.findByTenantOrderByCodeAsc(tenant).size());
        model.addAttribute("fiscalYears", fiscalYearRepo.findByTenantOrderByStartDateDesc(tenant));
        return "intra/accounting/setup";
    }

    @PostMapping("/init")
    public String initialize(@AuthenticationPrincipal CustomUserDetails principal, RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        if (setupService.isInitialized(tenant)) {
            ra.addFlashAttribute("errorMessage", "La comptabilité est déjà initialisée pour cette entreprise.");
        } else {
            setupService.initializeForTenant(tenant);
            ra.addFlashAttribute("successMessage",
                    "Comptabilité initialisée avec succès — PCG standard, journaux et exercice courant créés.");
        }
        return "redirect:/intra/accounting/setup";
    }
}
