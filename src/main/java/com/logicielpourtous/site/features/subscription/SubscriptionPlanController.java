package com.logicielpourtous.site.features.subscription;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/subscription-plans")
public class SubscriptionPlanController {

    private final SubscriptionPlanRepository     planRepository;
    private final SubscriptionCategoryRepository categoryRepository;

    public SubscriptionPlanController(SubscriptionPlanRepository planRepository,
                                      SubscriptionCategoryRepository categoryRepository) {
        this.planRepository     = planRepository;
        this.categoryRepository = categoryRepository;
    }

    @InitBinder("plan")
    public void initBinder(WebDataBinder binder) {
        binder.setDisallowedFields("category", "billingModel", "defaultPeriod");
    }

    @GetMapping
    public String list(Model model) {
        populate(model, new SubscriptionPlan());
        return "feature/subscription/plans/list";
    }

    @GetMapping("/edit/{id}")
    public String edit(@PathVariable Long id, Model model) {
        populate(model, planRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Plan introuvable : " + id)));
        return "feature/subscription/plans/list";
    }

    @PostMapping("/save")
    public String save(@ModelAttribute SubscriptionPlan plan,
                       @RequestParam(required = false) Long categoryId,
                       @RequestParam(required = false) String billingModel,
                       @RequestParam(required = false) String defaultPeriod,
                       @RequestParam(required = false, defaultValue = "0") Integer defaultIncludedHours,
                       @RequestParam(required = false, defaultValue = "0") Integer defaultIncludedMins,
                       RedirectAttributes attrs) {

        if (categoryId != null) {
            categoryRepository.findById(categoryId).ifPresent(plan::setCategory);
        }
        if (billingModel != null && !billingModel.isBlank()) {
            plan.setBillingModel(SubscriptionBillingModel.valueOf(billingModel));
        }
        if (defaultPeriod != null && !defaultPeriod.isBlank()) {
            plan.setDefaultPeriod(SubscriptionPeriod.valueOf(defaultPeriod));
        } else {
            plan.setDefaultPeriod(null);
        }

        int total = defaultIncludedHours * 60 + defaultIncludedMins;
        plan.setDefaultIncludedMinutes(total > 0 ? total : null);

        // Nettoyer les champs non-pertinents selon le modèle
        SubscriptionBillingModel bm = plan.getBillingModel();
        if (bm == SubscriptionBillingModel.FORFAIT) {
            plan.setDefaultHourlyRate(null);
            plan.setDefaultUnitPrice(null);
            plan.setUnitLabel(null);
        } else if (bm == SubscriptionBillingModel.HOURLY) {
            plan.setDefaultIncludedMinutes(null);
            plan.setDefaultPricePerPeriod(null);
            plan.setDefaultPeriod(null);
            plan.setDefaultUnitPrice(null);
            plan.setUnitLabel(null);
        } else if (bm == SubscriptionBillingModel.PER_USER || bm == SubscriptionBillingModel.PER_DEVICE) {
            plan.setDefaultIncludedMinutes(null);
            plan.setDefaultPricePerPeriod(null);
            plan.setDefaultHourlyRate(null);
            plan.setUnitLabel(null); // libellé implicite (personne / appareil)
        } else { // PER_UNIT
            plan.setDefaultIncludedMinutes(null);
            plan.setDefaultPricePerPeriod(null);
            plan.setDefaultHourlyRate(null);
        }

        planRepository.save(plan);
        attrs.addFlashAttribute("successMessage", "Formule enregistrée.");
        return "redirect:/admin/subscription-plans";
    }

    @GetMapping("/delete/{id}")
    public String delete(@PathVariable Long id, RedirectAttributes attrs) {
        planRepository.deleteById(id);
        attrs.addFlashAttribute("successMessage", "Formule supprimée.");
        return "redirect:/admin/subscription-plans";
    }

    /** Fragment HTMX : champs de facturation selon le modèle */
    @GetMapping("/billing-fields")
    public String billingFields(@RequestParam SubscriptionBillingModel billingModel,
                                @RequestParam(required = false) Long planId,
                                Model model) {
        SubscriptionPlan plan = planId != null
                ? planRepository.findById(planId).orElse(new SubscriptionPlan())
                : new SubscriptionPlan();
        plan.setBillingModel(billingModel);
        model.addAttribute("plan",    plan);
        model.addAttribute("periods", SubscriptionPeriod.values());
        return "feature/subscription/plans/_billing-fields :: billing-fields";
    }

    private void populate(Model model, SubscriptionPlan plan) {
        model.addAttribute("plans",         planRepository.findAllOrdered());
        model.addAttribute("plan",          plan);
        model.addAttribute("categories",    categoryRepository.findAllByOrderBySortOrderAscLabelAsc());
        model.addAttribute("billingModels", SubscriptionBillingModel.values());
        model.addAttribute("periods",       SubscriptionPeriod.values());
    }
}
