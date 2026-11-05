package com.logicielpourtous.site.features.intra.widget.providers;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.intra.widget.IntraWidgetProvider;
import com.logicielpourtous.site.features.intra.widget.SubValue;
import com.logicielpourtous.site.features.intra.widget.WidgetUtils;
import com.logicielpourtous.site.features.order.Order;
import com.logicielpourtous.site.features.order.OrderLineRepository;
import com.logicielpourtous.site.features.order.OrderRepository;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.util.List;

@Component
public class OrdersDashboardWidget implements IntraWidgetProvider {

    private final OrderRepository     orderRepository;
    private final OrderLineRepository orderLineRepository;

    public OrdersDashboardWidget(OrderRepository orderRepository,
                                 OrderLineRepository orderLineRepository) {
        this.orderRepository     = orderRepository;
        this.orderLineRepository = orderLineRepository;
    }

    @Override public String key()      { return "ORDERS_DASHBOARD"; }
    @Override public String template() { return "intra/widgets/_multi"; }

    @Override
    public void populate(Model model, CustomUserDetails user) {
        model.addAttribute("subValues", List.of(
                new SubValue("En cours HT",   WidgetUtils.formatAmount(orderLineRepository.sumTotalHTByOrderStatuses(
                        List.of(Order.OrderStatus.PENDING, Order.OrderStatus.CONFIRMED, Order.OrderStatus.IN_PROGRESS))), "#0ea5e9"),
                new SubValue("En attente",    String.valueOf(orderRepository.countByStatus(Order.OrderStatus.PENDING)),     "#94a3b8"),
                new SubValue("Confirmées",    String.valueOf(orderRepository.countByStatus(Order.OrderStatus.CONFIRMED)),   "#3b82f6"),
                new SubValue("En cours",      String.valueOf(orderRepository.countByStatus(Order.OrderStatus.IN_PROGRESS)), "#f59e0b"),
                new SubValue("Livrées",       String.valueOf(orderRepository.countByStatus(Order.OrderStatus.DELIVERED)),   "#10b981"),
                new SubValue("Annulées",      String.valueOf(orderRepository.countByStatus(Order.OrderStatus.CANCELLED)),   "#ef4444")
        ));
    }
}
