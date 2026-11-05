package com.logicielpourtous.site.features.site;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class MenuService {

    @Autowired private PageRepository pageRepository;
    @Autowired private CategoryRepository categoryRepository;

    public void swapOrder(Navigable itemA, Navigable itemB) {
        // 1. On échange les valeurs numériques en mémoire
        int orderA = itemA.getOrder();
        int orderB = itemB.getOrder();

        itemA.setOrder(orderB);
        itemB.setOrder(orderA);

        // 2. On sauvegarde chaque objet selon son type réel
        saveNavigable(itemA);
        saveNavigable(itemB);
    }

    public void saveNavigable(Navigable item) {
        if (item instanceof Page p) {
            pageRepository.save(p);
        } else if (item instanceof Category c) {
            categoryRepository.save(c);
        }
    }
}