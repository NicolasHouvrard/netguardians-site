package com.logicielpourtous.site.features.pages;

import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;

/**
 * Menu public, ajoute au modele des pages. Limite aux controleurs de PAGES ({@code @Controller}) : un
 * {@code @ControllerAdvice} global s'applique aussi aux endpoints d'actuator, qui n'ont pas de session
 * de base ouverte (open-in-view ne couvre que les controleurs MVC) ; la lecture paresseuse de
 * {@code MenuItem.children} y echouait ({@code LazyInitializationException}) des qu'un element de menu
 * existait, ce qui faisait repondre 500 a {@code /actuator/health}.
 */
@ControllerAdvice(annotations = Controller.class)
public class PublicMenuAdvice {

    private final MenuItemRepository menuItemRepository;

    public PublicMenuAdvice(MenuItemRepository menuItemRepository) {
        this.menuItemRepository = menuItemRepository;
    }

    // Transaction propre : la lecture des enfants ne depend pas d'une session ouverte par la requete.
    @Transactional(readOnly = true)
    @ModelAttribute("mainMenuItems")
    public List<MenuItem> mainMenuItems() {
        List<MenuItem> items = menuItemRepository
                .findByPositionAndParentIsNullAndActiveTrueOrderBySortOrderAsc(MenuPosition.MAIN);
        items.forEach(item -> item.getChildren().size());
        return items;
    }
}
