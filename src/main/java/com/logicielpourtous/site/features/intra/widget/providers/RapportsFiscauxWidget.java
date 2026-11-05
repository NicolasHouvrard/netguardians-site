package com.logicielpourtous.site.features.intra.widget.providers;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.intra.widget.IntraWidgetProvider;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.util.List;

@Component
public class RapportsFiscauxWidget implements IntraWidgetProvider {

    @Override public String key() { return "RAPPORTS_FISCAUX"; }
    @Override public String template() { return "intra/widgets/_links"; }

    @Override
    public void populate(Model model, CustomUserDetails user) {
        model.addAttribute("rows", List.of(
                List.of("fas fa-scale-balanced", "Balance générale",   "/intra/accounting/reports/balance"),
                List.of("fas fa-book-open",       "Grand livre",        "/intra/accounting/reports/grand-livre"),
                List.of("fas fa-landmark",         "Bilan",              "/intra/accounting/reports/bilan"),
                List.of("fas fa-chart-line",       "Compte de résultat", "/intra/accounting/reports/compte-resultat"),
                List.of("fas fa-file-alt",         "Liasses fiscales",   "/intra/accounting/fiscal")
        ));
    }
}
