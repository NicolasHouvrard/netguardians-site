package com.logicielpourtous.site.features.pages;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/intra/menu")
public class MenuEditorController {

    private static final Logger log = LoggerFactory.getLogger(MenuEditorController.class);

    private final MenuItemRepository menuItemRepository;
    private final PublicPageRepository pageRepository;

    public MenuEditorController(MenuItemRepository menuItemRepository,
                                PublicPageRepository pageRepository) {
        this.menuItemRepository = menuItemRepository;
        this.pageRepository = pageRepository;
    }

    // ─── Editor ──────────────────────────────────────────────────────────────

    @GetMapping({"", "/"})
    public String editor(Model model) {
        List<MenuItem> menuItems = menuItemRepository
                .findByPositionAndParentIsNullAndActiveTrueOrderBySortOrderAsc(MenuPosition.MAIN);
        // Load children eagerly to avoid lazy loading in template
        menuItems.forEach(item -> item.getChildren().size());

        model.addAttribute("menuItems", menuItems);
        model.addAttribute("pages", pageRepository.findByPageTypeOrderByTitleAsc(PageType.PAGE));
        return "intra/menu/editor";
    }

    // ─── Add item ────────────────────────────────────────────────────────────

    @PostMapping("/items")
    public String addItem(@RequestParam String label,
                          @RequestParam(required = false) String url,
                          @RequestParam(required = false) Long pageId,
                          @RequestParam(defaultValue = "MAIN") String position,
                          @RequestParam(defaultValue = "_self") String target,
                          @RequestParam(required = false) Long parentId,
                          RedirectAttributes ra) {
        MenuItem item = new MenuItem();
        item.setLabel(label.trim());
        item.setUrl(url != null && !url.isBlank() ? url.trim() : null);
        item.setTarget(target);
        item.setPosition(MenuPosition.valueOf(position));
        item.setActive(true);

        if (pageId != null) {
            pageRepository.findById(pageId).ifPresent(item::setLinkedPage);
        }
        if (parentId != null) {
            menuItemRepository.findById(parentId).ifPresent(item::setParent);
        }

        // Compute sortOrder = max existing + 1
        List<MenuItem> siblings = menuItemRepository
                .findByPositionAndParentIsNullAndActiveTrueOrderBySortOrderAsc(item.getPosition());
        item.setSortOrder(siblings.size());

        menuItemRepository.save(item);
        ra.addFlashAttribute("successMessage", "Élément de menu ajouté.");
        return "redirect:/intra/menu";
    }

    // ─── Delete item ─────────────────────────────────────────────────────────

    @PostMapping("/items/{id}/delete")
    public String deleteItem(@PathVariable Long id, RedirectAttributes ra) {
        menuItemRepository.deleteById(id);
        ra.addFlashAttribute("successMessage", "Élément supprimé.");
        return "redirect:/intra/menu";
    }

    // ─── Update item ─────────────────────────────────────────────────────────

    @PostMapping("/items/{id}/update")
    public String updateItem(@PathVariable Long id,
                             @RequestParam(required = false) String label,
                             @RequestParam(required = false) String url,
                             @RequestParam(required = false) Integer sortOrder,
                             @RequestParam(required = false, defaultValue = "false") boolean active,
                             @RequestParam(required = false) Long parentId,
                             RedirectAttributes ra) {
        menuItemRepository.findById(id).ifPresent(item -> {
            if (label != null && !label.isBlank()) item.setLabel(label.trim());
            item.setUrl(url != null && !url.isBlank() ? url.trim() : null);
            if (sortOrder != null) item.setSortOrder(sortOrder);
            item.setActive(active);
            if (parentId != null && !parentId.equals(id)) {
                menuItemRepository.findById(parentId).ifPresent(item::setParent);
            } else {
                item.setParent(null);
            }
            menuItemRepository.save(item);
        });
        ra.addFlashAttribute("successMessage", "Élément mis à jour.");
        return "redirect:/intra/menu";
    }

    // ─── Save order ──────────────────────────────────────────────────────────

    @PostMapping("/save-order")
    public String saveOrder(@RequestParam String orderJson, RedirectAttributes ra) {
        try {
            // Parse "[1,2,3]" without ObjectMapper
            String cleaned = orderJson.trim().replaceAll("[\\[\\]\\s]", "");
            List<Long> ids = cleaned.isEmpty() ? List.of() :
                    Arrays.stream(cleaned.split(","))
                          .map(Long::parseLong)
                          .collect(Collectors.toList());
            for (int i = 0; i < ids.size(); i++) {
                int finalI = i;
                menuItemRepository.findById(ids.get(i)).ifPresent(item -> {
                    item.setSortOrder(finalI);
                    menuItemRepository.save(item);
                });
            }
            ra.addFlashAttribute("successMessage", "Ordre du menu sauvegardé.");
        } catch (Exception e) {
            log.error("Error saving menu order", e);
            ra.addFlashAttribute("errorMessage", "Erreur lors de la sauvegarde de l'ordre.");
        }
        return "redirect:/intra/menu";
    }
}
