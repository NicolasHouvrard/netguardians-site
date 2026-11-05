package com.logicielpourtous.site.features.intra.widget.providers;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.intra.widget.IntraWidgetProvider;
import com.logicielpourtous.site.features.shop.ShopOrder;
import com.logicielpourtous.site.features.shop.ShopOrderRepository;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

@Component
public class PendingShopOrdersWidget implements IntraWidgetProvider {

    private final ShopOrderRepository repo;

    public PendingShopOrdersWidget(ShopOrderRepository repo) { this.repo = repo; }

    @Override public String key() { return "PENDING_SHOP_ORDERS"; }

    @Override
    public void populate(Model model, CustomUserDetails user) {
        model.addAttribute("value", String.valueOf(repo.countByStatus(ShopOrder.OrderStatus.PENDING_VALIDATION)));
    }
}
