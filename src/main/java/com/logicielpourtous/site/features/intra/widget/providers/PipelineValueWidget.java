package com.logicielpourtous.site.features.intra.widget.providers;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.intra.widget.IntraWidgetProvider;
import com.logicielpourtous.site.features.intra.widget.WidgetUtils;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.math.BigDecimal;

@Component
public class PipelineValueWidget implements IntraWidgetProvider {

    private final CustomerRepository customerRepository;

    public PipelineValueWidget(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    @Override public String key() { return "PIPELINE_VALUE"; }

    @Override
    public void populate(Model model, CustomUserDetails user) {
        BigDecimal total = customerRepository.findProspects().stream()
                .filter(p -> p.getEstimatedValue() != null)
                .map(p -> p.getEstimatedValue())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        model.addAttribute("value", WidgetUtils.formatAmount(total));
    }
}
