package com.logicielpourtous.site.features.ticketing;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TicketRoutingRuleRepository extends JpaRepository<TicketRoutingRule, Long> {
    List<TicketRoutingRule> findByActiveTrueOrderBySortOrderAsc();
    List<TicketRoutingRule> findAllByOrderBySortOrderAsc();
}
