package com.logicielpourtous.site.features.espace;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.subscription.Subscription;
import com.logicielpourtous.site.features.subscription.SubscriptionRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

@Controller
@RequestMapping("/espace/subscriptions")
public class EspaceSubscriptionController {

    private final SubscriptionRepository subscriptionRepository;
    private final EspaceContextService   ctx;

    public EspaceSubscriptionController(SubscriptionRepository subscriptionRepository,
                                        EspaceContextService ctx) {
        this.subscriptionRepository = subscriptionRepository;
        this.ctx                    = ctx;
    }

    @GetMapping
    public String list(@AuthenticationPrincipal CustomUserDetails userDetails,
                       HttpSession session, Model model) {
        Customer customer = ctx.loadCustomer(userDetails, session);
        List<Subscription> subs = subscriptionRepository
                .findByCustomerIdOrderByActiveDescStartDateDesc(customer.getId());
        model.addAttribute("customer",      customer);
        model.addAttribute("subscriptions", subs);
        return "espace/subscriptions";
    }
}
