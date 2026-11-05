package com.logicielpourtous.site.features.pages.widget;

import com.logicielpourtous.site.features.esn.PartnerEsnRepository;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class EsnCountWidget implements PublicWidget {

    private final PartnerEsnRepository esnRepository;

    public EsnCountWidget(PartnerEsnRepository esnRepository) {
        this.esnRepository = esnRepository;
    }

    @Override
    public String getId() { return "esn-count"; }

    @Override
    public String getLabel() { return "Nombre d'ESN partenaires"; }

    @Override
    public String getDescription() { return "Total d'ESN dans le réseau"; }

    @Override
    public Map<String, Object> getData(Map<String, String> params) {
        return Map.of(
            "count", esnRepository.count(),
            "label", "ESN Partenaires"
        );
    }
}
