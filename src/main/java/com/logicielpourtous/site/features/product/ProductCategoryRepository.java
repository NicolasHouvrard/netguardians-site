package com.logicielpourtous.site.features.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface ProductCategoryRepository extends JpaRepository<ProductCategory, Long> {
    Optional<ProductCategory> findByNameIgnoreCase(String name);
    List<ProductCategory> findAllByOrderByNameAsc();

    @Query("SELECT DISTINCT c FROM ProductCategory c LEFT JOIN FETCH c.children WHERE c.parent IS NULL ORDER BY c.name ASC")
    List<ProductCategory> findRootCategoriesWithChildren();

    boolean existsByParentId(Long parentId);

    @Query("SELECT COUNT(p) FROM Product p WHERE p.category.id = :categoryId")
    long countProductsByCategoryId(@Param("categoryId") Long categoryId);
}
