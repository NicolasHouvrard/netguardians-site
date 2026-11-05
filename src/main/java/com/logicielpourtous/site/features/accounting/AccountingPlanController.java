package com.logicielpourtous.site.features.accounting;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.Tenant;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/intra/accounting/plan")
public class AccountingPlanController {

    private final AccountingAccountRepository repo;
    private final AccountingSetupService setupService;

    public AccountingPlanController(AccountingAccountRepository repo, AccountingSetupService setupService) {
        this.repo = repo;
        this.setupService = setupService;
    }

    @GetMapping
    public String list(@AuthenticationPrincipal CustomUserDetails principal, Model model) {
        Tenant tenant = principal.getTenant();
        if (!setupService.isInitialized(tenant)) setupService.initializeForTenant(tenant);
        List<AccountingAccount> accounts = repo.findByTenantOrderByNumberAsc(tenant);
        model.addAttribute("accounts", accounts);
        model.addAttribute("accountsByClass", accounts.stream()
                .collect(Collectors.groupingBy(AccountingAccount::getAccountClass, TreeMap::new, Collectors.toList())));
        model.addAttribute("newAccount", new AccountingAccount());
        return "intra/accounting/plan/list";
    }

    @PostMapping("/save")
    public String save(@AuthenticationPrincipal CustomUserDetails principal,
                       @ModelAttribute AccountingAccount account, RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        if (account.getId() == null && repo.existsByNumberAndTenant(account.getNumber(), tenant)) {
            ra.addFlashAttribute("errorMessage", "Le compte " + account.getNumber() + " existe déjà.");
            return "redirect:/intra/accounting/plan";
        }
        account.setTenant(tenant);
        repo.save(account);
        ra.addFlashAttribute("successMessage", "Compte " + account.getNumber() + " sauvegardé.");
        return "redirect:/intra/accounting/plan";
    }

    @PostMapping("/{id}/toggle")
    public String toggle(@PathVariable Long id, RedirectAttributes ra) {
        repo.findById(id).ifPresent(a -> { a.setActive(!a.isActive()); repo.save(a); });
        return "redirect:/intra/accounting/plan";
    }
}
