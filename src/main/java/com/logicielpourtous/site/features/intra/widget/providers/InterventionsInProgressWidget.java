package com.logicielpourtous.site.features.intra.widget.providers;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.intervention.Intervention;
import com.logicielpourtous.site.features.intervention.InterventionRepository;
import com.logicielpourtous.site.features.intra.widget.IntraWidgetProvider;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

@Component
public class InterventionsInProgressWidget implements IntraWidgetProvider {

    private final InterventionRepository repo;

    public InterventionsInProgressWidget(InterventionRepository repo) { this.repo = repo; }

    @Override public String key() { return "INTERVENTIONS_IN_PROGRESS"; }

    @Override
    public void populate(Model model, CustomUserDetails user) {
        model.addAttribute("value", String.valueOf(repo.countByStatus(Intervention.InterventionStatus.IN_PROGRESS)));
    }
}
