package com.logicielpourtous.site.features.intra.widget.providers;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.intra.widget.IntraWidgetProvider;
import com.logicielpourtous.site.features.order.Order;
import com.logicielpourtous.site.features.order.OrderRepository;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

@Component
public class CommandesEnCoursWidget implements IntraWidgetProvider {

    private final OrderRepository repo;

    public CommandesEnCoursWidget(OrderRepository repo) { this.repo = repo; }

    @Override public String key() { return "COMMANDES_EN_COURS"; }

    @Override
    public void populate(Model model, CustomUserDetails user) {
        long count = repo.countByStatus(Order.OrderStatus.CONFIRMED)
                   + repo.countByStatus(Order.OrderStatus.IN_PROGRESS);
        model.addAttribute("value", String.valueOf(count));
    }
}
