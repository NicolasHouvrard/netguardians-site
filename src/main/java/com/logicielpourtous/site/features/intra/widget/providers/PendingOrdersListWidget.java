package com.logicielpourtous.site.features.intra.widget.providers;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.intra.widget.IntraWidgetProvider;
import com.logicielpourtous.site.features.shop.ShopOrder;
import com.logicielpourtous.site.features.shop.ShopOrderRepository;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

@Component
public class PendingOrdersListWidget implements IntraWidgetProvider {

    private final ShopOrderRepository repo;

    public PendingOrdersListWidget(ShopOrderRepository repo) { this.repo = repo; }

    @Override public String key() { return "PENDING_ORDERS_LIST"; }
    @Override public String template() { return "intra/widgets/_order-list"; }

    @Override
    public void populate(Model model, CustomUserDetails user) {
        model.addAttribute("orders",
                repo.findByStatusOrderByCreatedAtDesc(ShopOrder.OrderStatus.PENDING_VALIDATION)
                        .stream().limit(5).toList());
    }
}
