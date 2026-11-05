package com.logicielpourtous.site.features.intra.widget.providers;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.intervention.Intervention;
import com.logicielpourtous.site.features.intervention.InterventionRepository;
import com.logicielpourtous.site.features.intra.widget.IntraWidgetProvider;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

@Component
public class InterventionsAFacturerWidget implements IntraWidgetProvider {

    private final InterventionRepository repo;

    public InterventionsAFacturerWidget(InterventionRepository repo) { this.repo = repo; }

    @Override public String key() { return "INTERVENTIONS_A_FACTURER"; }

    @Override
    public void populate(Model model, CustomUserDetails user) {
        long count = repo.findByStatusAndLinkedInvoiceIsNullOrderByDateAsc(Intervention.InterventionStatus.COMPLETED).size();
        model.addAttribute("value", String.valueOf(count));
    }
}
