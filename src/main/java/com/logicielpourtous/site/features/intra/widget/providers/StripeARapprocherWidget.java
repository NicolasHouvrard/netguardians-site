package com.logicielpourtous.site.features.intra.widget.providers;

import com.logicielpourtous.site.features.accounting.StripeTransactionRepository;
import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.intra.widget.IntraWidgetProvider;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

@Component
public class StripeARapprocherWidget implements IntraWidgetProvider {

    private final StripeTransactionRepository repo;

    public StripeARapprocherWidget(StripeTransactionRepository repo) { this.repo = repo; }

    @Override public String key() { return "STRIPE_A_RAPPROCHER"; }

    @Override
    public void populate(Model model, CustomUserDetails user) {
        Tenant t = user != null ? user.getTenant() : null;
        String value = t != null ? String.valueOf(repo.countByTenantAndReconciledFalse(t)) : "—";
        model.addAttribute("value", value);
    }
}
