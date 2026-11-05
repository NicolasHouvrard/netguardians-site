package com.logicielpourtous.site.features.shop;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ShopOrderRepository extends JpaRepository<ShopOrder, Long> {

    Optional<ShopOrder> findByReference(String reference);

    Optional<ShopOrder> findByStripeSessionId(String stripeSessionId);

    @Query("SELECT o FROM ShopOrder o ORDER BY o.createdAt DESC")
    List<ShopOrder> findAllOrderByCreatedAtDesc();

    List<ShopOrder> findByStatusOrderByCreatedAtDesc(ShopOrder.OrderStatus status);

    long countByStatus(ShopOrder.OrderStatus status);

    List<ShopOrder> findByEmailOrderByCreatedAtDesc(String email);

    List<ShopOrder> findByCustomer_IdOrderByCreatedAtDesc(Long customerId);
}
