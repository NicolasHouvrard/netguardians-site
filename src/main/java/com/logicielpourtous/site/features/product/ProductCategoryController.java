package com.logicielpourtous.site.features.product;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.util.List;

@Controller
@RequestMapping("/intra/products/categories")
public class ProductCategoryController {

    private final ProductCategoryRepository categoryRepository;

    public ProductCategoryController(ProductCategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @GetMapping
    public String list(Model model) {
        List<ProductCategory> roots = categoryRepository.findRootCategoriesWithChildren();
        model.addAttribute("roots", roots);
        return "intra/products/categories/list";
    }

    @PostMapping
    public String create(@RequestParam String name,
                         @RequestParam(required = false) Long parentId,
                         @RequestParam(required = false) String icon,
                         @RequestParam(required = false) String color,
                         RedirectAttributes ra) {
        ProductCategory cat = new ProductCategory();
        cat.setName(name);
        cat.setIcon(icon != null && !icon.isBlank() ? icon : "fas fa-tag");
        cat.setColor(color != null && !color.isBlank() ? color : "#64748b");
        if (parentId != null) {
            categoryRepository.findById(parentId).ifPresent(cat::setParent);
        }
        categoryRepository.save(cat);
        ra.addFlashAttribute("successMessage", "Catégorie « " + name + " » créée.");
        return "redirect:/intra/products/categories";
    }

    @PostMapping("/{id}/update")
    public String update(@PathVariable Long id,
                         @RequestParam String name,
                         @RequestParam(required = false) Long parentId,
                         @RequestParam(required = false) String icon,
                         @RequestParam(required = false) String color,
                         RedirectAttributes ra) {
        ProductCategory cat = categoryRepository.findById(id).orElse(null);
        if (cat == null) return "redirect:/intra/products/categories";
        cat.setName(name);
        cat.setIcon(icon != null && !icon.isBlank() ? icon : "fas fa-tag");
        cat.setColor(color != null && !color.isBlank() ? color : "#64748b");
        if (parentId != null && !parentId.equals(id)) {
            categoryRepository.findById(parentId).ifPresent(cat::setParent);
        } else {
            cat.setParent(null);
        }
        categoryRepository.save(cat);
        ra.addFlashAttribute("successMessage", "Catégorie « " + name + " » mise à jour.");
        return "redirect:/intra/products/categories";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        ProductCategory cat = categoryRepository.findById(id).orElse(null);
        if (cat == null) return "redirect:/intra/products/categories";
        if (categoryRepository.existsByParentId(id)) {
            ra.addFlashAttribute("errorMessage", "Impossible : cette catégorie contient des sous-catégories.");
            return "redirect:/intra/products/categories";
        }
        if (categoryRepository.countProductsByCategoryId(id) > 0) {
            ra.addFlashAttribute("errorMessage", "Impossible : des produits utilisent cette catégorie.");
            return "redirect:/intra/products/categories";
        }
        String catName = cat.getName();
        categoryRepository.delete(cat);
        ra.addFlashAttribute("successMessage", "Catégorie « " + catName + " » supprimée.");
        return "redirect:/intra/products/categories";
    }
}
