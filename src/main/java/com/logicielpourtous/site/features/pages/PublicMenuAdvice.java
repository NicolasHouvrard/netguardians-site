package com.logicielpourtous.site.features.pages;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;

@ControllerAdvice
public class PublicMenuAdvice {

    private final MenuItemRepository menuItemRepository;

    public PublicMenuAdvice(MenuItemRepository menuItemRepository) {
        this.menuItemRepository = menuItemRepository;
    }

    @ModelAttribute("mainMenuItems")
    public List<MenuItem> mainMenuItems() {
        List<MenuItem> items = menuItemRepository
                .findByPositionAndParentIsNullAndActiveTrueOrderBySortOrderAsc(MenuPosition.MAIN);
        items.forEach(item -> item.getChildren().size());
        return items;
    }
}
