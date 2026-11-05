package com.logicielpourtous.site.features.pages.widget;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class RequestFormWidget implements PublicWidget {

    @Override
    public String getId() { return "request-form"; }

    @Override
    public String getLabel() { return "Formulaire de demande"; }

    @Override
    public String getDescription() { return "Formulaire de contact / ticket support intégrable dans une page publique"; }

    @Override
    public Map<String, Object> getData(Map<String, String> params) {
        return Map.of();
    }
}
