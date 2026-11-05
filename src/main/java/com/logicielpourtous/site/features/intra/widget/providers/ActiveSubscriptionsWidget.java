package com.logicielpourtous.site.features.intra.widget.providers;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.intra.widget.IntraWidgetProvider;
import com.logicielpourtous.site.features.subscription.SubscriptionRepository;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

@Component
public class ActiveSubscriptionsWidget implements IntraWidgetProvider {

    private final SubscriptionRepository repo;

    public ActiveSubscriptionsWidget(SubscriptionRepository repo) { this.repo = repo; }

    @Override public String key() { return "ACTIVE_SUBSCRIPTIONS"; }

    @Override
    public void populate(Model model, CustomUserDetails user) {
        model.addAttribute("value", String.valueOf(repo.countByActiveTrue()));
    }
}
