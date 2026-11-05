package com.logicielpourtous.site.features.accounting;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.Tenant;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/intra/accounting/fiscal-years")
public class FiscalYearController {

    private final FiscalYearRepository repo;

    public FiscalYearController(FiscalYearRepository repo) { this.repo = repo; }

    @GetMapping
    public String list(@AuthenticationPrincipal CustomUserDetails principal, Model model) {
        Tenant tenant = principal.getTenant();
        model.addAttribute("years", repo.findByTenantOrderByStartDateDesc(tenant));
        model.addAttribute("newYear", new FiscalYear());
        return "intra/accounting/fiscal-years/list";
    }

    @PostMapping("/save")
    public String save(@AuthenticationPrincipal CustomUserDetails principal,
                       @ModelAttribute FiscalYear year, RedirectAttributes ra) {
        year.setTenant(principal.getTenant());
        repo.save(year);
        ra.addFlashAttribute("successMessage", "Exercice " + year.getLabel() + " sauvegardé.");
        return "redirect:/intra/accounting/fiscal-years";
    }

    @PostMapping("/{id}/set-current")
    @Transactional
    public String setCurrent(@AuthenticationPrincipal CustomUserDetails principal,
                             @PathVariable Long id, RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        repo.clearCurrentFlagsByTenant(tenant);
        repo.findById(id).ifPresent(y -> { y.setCurrent(true); repo.save(y); });
        ra.addFlashAttribute("successMessage", "Exercice courant mis à jour.");
        return "redirect:/intra/accounting/fiscal-years";
    }

    @PostMapping("/{id}/close")
    @Transactional
    public String close(@PathVariable Long id, RedirectAttributes ra) {
        repo.findById(id).ifPresent(y -> {
            y.setClosed(true);
            y.setCurrent(false);
            repo.save(y);
        });
        ra.addFlashAttribute("successMessage", "Exercice clôturé.");
        return "redirect:/intra/accounting/fiscal-years";
    }
}
