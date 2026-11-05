package com.logicielpourtous.site.features.shop;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShopPriceHistoryRepository extends JpaRepository<ShopPriceHistory, Long> {
    List<ShopPriceHistory> findByProductIdOrderByChangedAtDesc(Long productId);
}
