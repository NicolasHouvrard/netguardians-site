package com.logicielpourtous.site.features.intra.widget.providers;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.crm.quote.QuoteRepository;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.intra.widget.IntraWidgetProvider;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

@Component
public class ProspectsPipelineWidget implements IntraWidgetProvider {

    private final CustomerRepository customerRepository;
    private final QuoteRepository    quoteRepository;

    public ProspectsPipelineWidget(CustomerRepository customerRepository,
                                   QuoteRepository quoteRepository) {
        this.customerRepository = customerRepository;
        this.quoteRepository    = quoteRepository;
    }

    @Override public String key() { return "PROSPECTS_PIPELINE"; }

    @Override
    public void populate(Model model, CustomUserDetails user) {
        model.addAttribute("value", String.valueOf(customerRepository.findProspects().size()));
    }
}
