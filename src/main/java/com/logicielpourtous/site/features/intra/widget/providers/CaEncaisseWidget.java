package com.logicielpourtous.site.features.intra.widget.providers;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.intra.widget.IntraWidgetProvider;
import com.logicielpourtous.site.features.intra.widget.WidgetUtils;
import com.logicielpourtous.site.features.invoice.InvoiceRepository;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

@Component
public class CaEncaisseWidget implements IntraWidgetProvider {

    private final InvoiceRepository repo;

    public CaEncaisseWidget(InvoiceRepository repo) { this.repo = repo; }

    @Override public String key() { return "CA_ENCAISSE"; }

    @Override
    public void populate(Model model, CustomUserDetails user) {
        model.addAttribute("value", WidgetUtils.formatAmount(repo.sumPaidSales()));
    }
}
