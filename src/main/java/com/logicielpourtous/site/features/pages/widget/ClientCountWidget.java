package com.logicielpourtous.site.features.pages.widget;

import com.logicielpourtous.site.features.customers.CustomerRepository;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ClientCountWidget implements PublicWidget {

    private final CustomerRepository customerRepository;

    public ClientCountWidget(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    @Override
    public String getId() { return "client-count"; }

    @Override
    public String getLabel() { return "Nombre de clients"; }

    @Override
    public String getDescription() { return "Total de clients dans la base"; }

    @Override
    public Map<String, Object> getData(Map<String, String> params) {
        return Map.of(
            "count", customerRepository.count(),
            "label", "Clients"
        );
    }
}
