package com.logicielpourtous.site.features.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;

public interface OrderLineRepository extends JpaRepository<OrderLine, Long> {

    @Query("SELECT COALESCE(SUM(l.totalHT), 0) FROM OrderLine l WHERE l.order.status IN :statuses")
    BigDecimal sumTotalHTByOrderStatuses(@Param("statuses") Collection<Order.OrderStatus> statuses);
}
