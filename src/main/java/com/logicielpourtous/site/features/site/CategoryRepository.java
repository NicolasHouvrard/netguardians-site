package com.logicielpourtous.site.features.site;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    // Récupère les catégories actives triées par position
    List<Category> findAllByActiveTrueOrderByPositionAsc();
    Optional<Category> findByPosition(Integer position);

    // Le "JOIN FETCH" force le chargement des pages en une seule requête SQL
    @Query("SELECT DISTINCT c FROM Category c LEFT JOIN FETCH c.pages ORDER BY c.position Asc")
    List<Category> findAllWithPages();

   
} 
