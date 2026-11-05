package com.logicielpourtous.site.features.shop;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface ShopProductRepository extends JpaRepository<ShopProduct, Long>, JpaSpecificationExecutor<ShopProduct> {

    Optional<ShopProduct> findBySlug(String slug);

    Optional<ShopProduct> findByProductId(Long productId);

    @Query("SELECT sp FROM ShopProduct sp JOIN FETCH sp.product p WHERE sp.published = true ORDER BY sp.displayOrder ASC, p.name ASC")
    List<ShopProduct> findPublishedOrderByDisplayOrder();

    @Query("SELECT sp FROM ShopProduct sp JOIN FETCH sp.product p ORDER BY sp.displayOrder ASC, p.name ASC")
    List<ShopProduct> findAllWithProduct();

    @Query("SELECT sp FROM ShopProduct sp JOIN FETCH sp.product p WHERE sp.published = true AND sp.vendor IS NULL ORDER BY sp.displayOrder ASC")
    List<ShopProduct> findPublishedPlatformProducts();

    @Query("SELECT sp FROM ShopProduct sp JOIN FETCH sp.product p WHERE sp.vendor.id = :vendorId ORDER BY p.name ASC")
    List<ShopProduct> findByVendorId(@Param("vendorId") Long vendorId);

    boolean existsByProductId(Long productId);

    @Query("SELECT s.product.id FROM ShopProduct s WHERE s.product.id IN :ids")
    Set<Long> findProductIdsIn(@Param("ids") Collection<Long> ids);

    @Query("SELECT s.product.id FROM ShopProduct s WHERE s.product.id IN :ids AND s.published = true")
    Set<Long> findPublishedProductIdsIn(@Param("ids") Collection<Long> ids);

    @Query(value = "SELECT sp FROM ShopProduct sp JOIN FETCH sp.product p WHERE sp.published = true " +
                   "AND (:brand IS NULL OR sp.brand = :brand) " +
                   "AND (:category IS NULL OR sp.shopCategory = :category) " +
                   "ORDER BY sp.displayOrder ASC, p.name ASC",
           countQuery = "SELECT COUNT(sp) FROM ShopProduct sp WHERE sp.published = true " +
                   "AND (:brand IS NULL OR sp.brand = :brand) " +
                   "AND (:category IS NULL OR sp.shopCategory = :category)")
    Page<ShopProduct> findPublishedFiltered(@Param("brand") String brand,
                                            @Param("category") String category,
                                            Pageable pageable);

    @Query("SELECT DISTINCT sp.brand FROM ShopProduct sp WHERE sp.published = true AND sp.brand IS NOT NULL ORDER BY sp.brand ASC")
    List<String> findDistinctBrands();

    @Query("SELECT DISTINCT sp.shopCategory FROM ShopProduct sp WHERE sp.published = true AND sp.shopCategory IS NOT NULL ORDER BY sp.shopCategory ASC")
    List<String> findDistinctCategories();
}
