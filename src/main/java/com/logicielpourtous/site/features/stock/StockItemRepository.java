package com.logicielpourtous.site.features.stock;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StockItemRepository extends JpaRepository<StockItem, Long> {

    Optional<StockItem> findByProductIdAndSiteId(Long productId, Long siteId);

    List<StockItem> findBySiteIdOrderByProductNameAsc(Long siteId);

    List<StockItem> findByProductIdOrderBySiteNameAsc(Long productId);

    @Query("SELECT s FROM StockItem s JOIN FETCH s.product JOIN FETCH s.site " +
           "WHERE s.minQuantity > 0 AND s.quantity <= s.minQuantity " +
           "ORDER BY s.site.name, s.product.name")
    List<StockItem> findLowStock();

    @Query("SELECT s FROM StockItem s JOIN FETCH s.product JOIN FETCH s.site " +
           "ORDER BY s.site.name, s.product.name")
    List<StockItem> findAllWithDetails();

    @Query("SELECT s FROM StockItem s JOIN FETCH s.product WHERE s.site.id = :siteId " +
           "ORDER BY s.product.name")
    List<StockItem> findBySiteIdWithProduct(@Param("siteId") Long siteId);

    @Query("SELECT COUNT(s) FROM StockItem s WHERE s.site.id = :siteId")
    long countBySiteId(@Param("siteId") Long siteId);

    @Query("SELECT COALESCE(SUM(s.quantity), 0) FROM StockItem s WHERE s.site.id = :siteId")
    long sumQuantityBySiteId(@Param("siteId") Long siteId);

    @Query("SELECT COUNT(s) FROM StockItem s WHERE s.site.id = :siteId AND s.minQuantity > 0 AND s.quantity <= s.minQuantity")
    long countLowStockBySiteId(@Param("siteId") Long siteId);
}
