package com.logicielpourtous.site.features.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByCustomerIdOrderByOrderDateDesc(Long customerId);
    List<Order> findByStatusOrderByOrderDateDesc(Order.OrderStatus status);
    List<Order> findAllByOrderByOrderDateDesc();
    long countByStatus(Order.OrderStatus status);
    List<Order> findByTicketIdOrderByCreatedAtDesc(Long ticketId);

    @Query("SELECT COALESCE(SUM(l.totalHT), 0) FROM OrderLine l WHERE l.order.status IN :statuses")
    BigDecimal sumTotalHTByStatuses(@Param("statuses") Collection<Order.OrderStatus> statuses);

    List<Order> findByIngramOrderNumberIsNotNullOrderByIngramOrderedAtDesc();
}
