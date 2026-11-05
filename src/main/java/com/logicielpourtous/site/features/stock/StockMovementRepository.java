package com.logicielpourtous.site.features.stock;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    @Query("SELECT m FROM StockMovement m JOIN FETCH m.stockItem si JOIN FETCH si.product JOIN FETCH si.site " +
           "ORDER BY m.createdAt DESC")
    List<StockMovement> findAllWithDetails();

    @Query("SELECT m FROM StockMovement m JOIN FETCH m.stockItem si JOIN FETCH si.product JOIN FETCH si.site " +
           "WHERE si.site.id = :siteId ORDER BY m.createdAt DESC")
    List<StockMovement> findBySiteIdOrderByCreatedAtDesc(@Param("siteId") Long siteId);

    @Query("SELECT m FROM StockMovement m JOIN FETCH m.stockItem si JOIN FETCH si.product JOIN FETCH si.site " +
           "WHERE si.id = :stockItemId ORDER BY m.createdAt DESC")
    List<StockMovement> findByStockItemIdOrderByCreatedAtDesc(@Param("stockItemId") Long stockItemId);

    List<StockMovement> findTop50ByOrderByCreatedAtDesc();
}
