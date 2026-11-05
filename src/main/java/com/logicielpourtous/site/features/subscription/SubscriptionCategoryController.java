package com.logicielpourtous.site.features.subscription;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/subscription-categories")
public class SubscriptionCategoryController {

    private final SubscriptionCategoryRepository categoryRepository;

    public SubscriptionCategoryController(SubscriptionCategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("categories", categoryRepository.findAllByOrderBySortOrderAscLabelAsc());
        model.addAttribute("category",   new SubscriptionCategory());
        return "feature/subscription/categories/list";
    }

    @GetMapping("/edit/{id}")
    public String edit(@PathVariable Long id, Model model) {
        model.addAttribute("categories", categoryRepository.findAllByOrderBySortOrderAscLabelAsc());
        model.addAttribute("category",   categoryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Catégorie introuvable : " + id)));
        return "feature/subscription/categories/list";
    }

    @PostMapping("/save")
    public String save(@ModelAttribute SubscriptionCategory category, RedirectAttributes attrs) {
        categoryRepository.save(category);
        attrs.addFlashAttribute("successMessage", "Catégorie enregistrée.");
        return "redirect:/admin/subscription-categories";
    }

    @GetMapping("/delete/{id}")
    public String delete(@PathVariable Long id, RedirectAttributes attrs) {
        categoryRepository.deleteById(id);
        attrs.addFlashAttribute("successMessage", "Catégorie supprimée.");
        return "redirect:/admin/subscription-categories";
    }
}
