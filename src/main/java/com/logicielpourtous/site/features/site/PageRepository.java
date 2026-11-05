package com.logicielpourtous.site.features.site;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;


public interface PageRepository extends JpaRepository<Page, Long> {
    Optional<Page>  findBySlug(String slug);
    Optional<Page> findFirstByIsHomePageTrue();

    boolean existsBySlugAndIdNot(String slug, Long id);

    // Pour la création pure
    boolean existsBySlug(String slug);

    List<Page> findAllByIsActiveTrue();
       @Query("SELECT DISTINCT p.category FROM Page p WHERE p.category IS NOT NULL")
    List<String> findDistinctCategories();
    
    // Pour ton header public
    List<Page> findAllByOrderByCategoryAscDisplayOrderAsc();

    List<Page> findByCategoryIsNull();

    // Trouver la page qui a une position spécifique dans une catégorie donnée
    Optional<Page> findByCategoryAndDisplayOrder(Category category, Integer displayOrder);
    
    // Pour les pages orphelines (sans catégorie)
    Optional<Page> findByCategoryIsNullAndDisplayOrder(Integer displayOrder);
    
    List<Page> findByCategoryIsNullOrderByDisplayOrderAsc();

    List<Page> findByCategoryIsNullAndIsPublishedTrueAndIsActiveTrueOrderByDisplayOrderAsc();
    List<Page> findByCategoryIsNullAndIsActiveTrue();

    List<Page> findByCategoryIsNullAndIsActiveFalse();

   
}
