package com.logicielpourtous.site.features.intra.widget.providers;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.intra.widget.IntraWidgetProvider;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

@Component
public class CustomersWidget implements IntraWidgetProvider {

    private final CustomerRepository repo;

    public CustomersWidget(CustomerRepository repo) { this.repo = repo; }

    @Override public String key() { return "CUSTOMERS"; }

    @Override
    public void populate(Model model, CustomUserDetails user) {
        model.addAttribute("value", String.valueOf(repo.count()));
    }
}
