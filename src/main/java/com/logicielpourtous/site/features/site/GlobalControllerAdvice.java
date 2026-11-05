package com.logicielpourtous.site.features.site;

import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest; // Bien vérifier l'import jakarta !
// ... autres imports

@ControllerAdvice
public class GlobalControllerAdvice {

    private final PageRepository pageRepository;

    private  final CategoryRepository categoryRepository;

    public GlobalControllerAdvice(PageRepository pageRepository, CategoryRepository categoryRepository) {
        this.pageRepository = pageRepository;
        this.categoryRepository = categoryRepository;
    }

   @ModelAttribute
public void addAttributes(Model model, HttpServletRequest request) {
    // 1. On récupère les données
    List<Category> categories = categoryRepository.findAllByActiveTrueOrderByPositionAsc();
    
    // Utilise bien les noms exacts de ton entité Page (ex: published au lieu de isPublished)
    List<Page> directPages = pageRepository.findByCategoryIsNullAndIsPublishedTrueAndIsActiveTrueOrderByDisplayOrderAsc();

    // 2. Construction de la liste mixte
    List<Object> fullMenu = new ArrayList<>();
    if (categories != null) fullMenu.addAll(categories);
    if (directPages != null) fullMenu.addAll(directPages);

    // 3. Tri sécurisé (gestion du null pour éviter les crashs)
    fullMenu.sort((a, b) -> {
        int posA = (a instanceof Category) ? ((Category) a).getPosition() : ((Page) a).getDisplayOrder();
        int posB = (b instanceof Category) ? ((Category) b).getPosition() : ((Page) b).getDisplayOrder();
        return Integer.compare(posA, posB);
    });

    // 4. Envoi au modèle
    model.addAttribute("fullMenu", fullMenu);
    model.addAttribute("currentUri", request.getRequestURI());
}
}