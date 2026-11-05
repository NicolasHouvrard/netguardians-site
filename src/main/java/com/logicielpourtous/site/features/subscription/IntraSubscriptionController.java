package com.logicielpourtous.site.features.subscription;

import com.logicielpourtous.site.features.customers.CustomerRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequestMapping("/intra/subscriptions")
public class IntraSubscriptionController {

    private final SubscriptionRepository subscriptionRepository;
    private final CustomerRepository     customerRepository;

    public IntraSubscriptionController(SubscriptionRepository subscriptionRepository,
                                       CustomerRepository customerRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.customerRepository     = customerRepository;
    }

    @GetMapping
    public String list(@RequestParam(required = false) Long customerId,
                       @RequestParam(required = false) Boolean activeOnly,
                       Model model) {
        List<Subscription> subscriptions;
        if (customerId != null) {
            subscriptions = subscriptionRepository
                    .findByCustomerIdOrderByActiveDescStartDateDesc(customerId);
        } else {
            subscriptions = subscriptionRepository.findAllOrderByCustomerAndActive();
        }
        if (Boolean.TRUE.equals(activeOnly)) {
            subscriptions = subscriptions.stream().filter(Subscription::isActive).toList();
        }

        model.addAttribute("subscriptions",      subscriptions);
        model.addAttribute("customers",          customerRepository.findAll());
        model.addAttribute("selectedCustomerId", customerId);
        model.addAttribute("activeOnly",         activeOnly);
        return "intra/subscriptions/list";
    }
}
