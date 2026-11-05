package com.logicielpourtous.site.features.shop;

import org.springframework.security.access.annotation.Secured;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;

@Controller
@RequestMapping("/intra/shop/pricing-rules")
@Secured({"ROLE_ADMIN", "ROLE_COMMERCIAL"})
public class PricingRuleController {

    private final PricingRuleRepository pricingRuleRepository;

    public PricingRuleController(PricingRuleRepository pricingRuleRepository) {
        this.pricingRuleRepository = pricingRuleRepository;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("rules", pricingRuleRepository.findAllByOrderByNameAsc());
        return "intra/shop/pricing-rules";
    }

    @PostMapping
    public String create(@RequestParam String name,
                         @RequestParam(defaultValue = "0") BigDecimal fixedMargin,
                         @RequestParam(defaultValue = "0") BigDecimal percentage,
                         @RequestParam(defaultValue = "false") boolean isDefault,
                         RedirectAttributes ra) {
        if (isDefault) {
            pricingRuleRepository.findFirstByIsDefaultTrue().ifPresent(r -> {
                r.setDefault(false);
                pricingRuleRepository.save(r);
            });
        }
        PricingRule rule = new PricingRule();
        rule.setName(name.trim());
        rule.setFixedMargin(fixedMargin);
        rule.setPercentage(percentage);
        rule.setDefault(isDefault);
        pricingRuleRepository.save(rule);
        ra.addFlashAttribute("successMessage", "Règle « " + rule.getName() + " » créée — " + rule.getSummary());
        return "redirect:/intra/shop/pricing-rules";
    }

    @PostMapping("/{id}/update")
    public String update(@PathVariable Long id,
                         @RequestParam String name,
                         @RequestParam(defaultValue = "0") BigDecimal fixedMargin,
                         @RequestParam(defaultValue = "0") BigDecimal percentage,
                         @RequestParam(defaultValue = "false") boolean isDefault,
                         RedirectAttributes ra) {
        PricingRule rule = pricingRuleRepository.findById(id).orElse(null);
        if (rule == null) return "redirect:/intra/shop/pricing-rules";
        if (isDefault && !rule.isDefault()) {
            pricingRuleRepository.findFirstByIsDefaultTrue().ifPresent(r -> {
                r.setDefault(false);
                pricingRuleRepository.save(r);
            });
        }
        rule.setName(name.trim());
        rule.setFixedMargin(fixedMargin);
        rule.setPercentage(percentage);
        rule.setDefault(isDefault);
        pricingRuleRepository.save(rule);
        ra.addFlashAttribute("successMessage", "Règle mise à jour — " + rule.getSummary());
        return "redirect:/intra/shop/pricing-rules";
    }

    @PostMapping("/{id}/set-default")
    public String setDefault(@PathVariable Long id, RedirectAttributes ra) {
        pricingRuleRepository.findFirstByIsDefaultTrue().ifPresent(r -> {
            r.setDefault(false);
            pricingRuleRepository.save(r);
        });
        pricingRuleRepository.findById(id).ifPresent(r -> {
            r.setDefault(true);
            pricingRuleRepository.save(r);
            ra.addFlashAttribute("successMessage", "Règle par défaut : « " + r.getName() + " ».");
        });
        return "redirect:/intra/shop/pricing-rules";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        pricingRuleRepository.deleteById(id);
        ra.addFlashAttribute("successMessage", "Règle supprimée.");
        return "redirect:/intra/shop/pricing-rules";
    }
}
