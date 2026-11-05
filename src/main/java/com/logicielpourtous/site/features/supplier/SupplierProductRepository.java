package com.logicielpourtous.site.features.supplier;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SupplierProductRepository extends JpaRepository<SupplierProduct, Long> {

    List<SupplierProduct> findByProductIdOrderBySupplierId(Long productId);

    @Query("SELECT sp FROM SupplierProduct sp JOIN FETCH sp.product p JOIN FETCH sp.supplier s WHERE sp.supplier.id = :supplierId ORDER BY p.name ASC")
    List<SupplierProduct> findBySupplierId(@Param("supplierId") Long supplierId);

    Optional<SupplierProduct> findBySupplierIdAndProductId(Long supplierId, Long productId);

    boolean existsBySupplierIdAndProductId(Long supplierId, Long productId);

    long countBySupplierId(Long supplierId);

    Optional<SupplierProduct> findBySupplierIdAndSupplierReference(Long supplierId, String supplierReference);

    @Query("SELECT sp.supplierReference FROM SupplierProduct sp WHERE sp.supplier.id = :supplierId AND sp.supplierReference IS NOT NULL")
    List<String> findSupplierReferencesBySupplierId(@Param("supplierId") Long supplierId);

    @Query("SELECT sp.product.id, MIN(sp.purchasePrice) FROM SupplierProduct sp WHERE sp.product.id IN :ids AND sp.purchasePrice IS NOT NULL GROUP BY sp.product.id")
    List<Object[]> findMinPaByProductIds(@Param("ids") Collection<Long> ids);

    @Query(value = "SELECT sp FROM SupplierProduct sp JOIN sp.product p WHERE sp.supplier.id = :supplierId " +
                   "AND (:q IS NULL OR LOWER(p.name) LIKE :q OR LOWER(sp.supplierReference) LIKE :q OR LOWER(sp.vendorName) LIKE :q) " +
                   "ORDER BY p.name ASC",
           countQuery = "SELECT COUNT(sp) FROM SupplierProduct sp JOIN sp.product p WHERE sp.supplier.id = :supplierId " +
                   "AND (:q IS NULL OR LOWER(p.name) LIKE :q OR LOWER(sp.supplierReference) LIKE :q OR LOWER(sp.vendorName) LIKE :q)")
    Page<SupplierProduct> findBySupplierIdFiltered(@Param("supplierId") Long supplierId,
                                                   @Param("q") String q,
                                                   Pageable pageable);
}
