package com.logicielpourtous.site.features.intra.widget.providers;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.intra.widget.IntraWidgetProvider;
import com.logicielpourtous.site.features.intra.widget.SubValue;
import com.logicielpourtous.site.features.invoice.Invoice;
import com.logicielpourtous.site.features.invoice.InvoiceRepository;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.util.List;

@Component
public class FacCliWidget implements IntraWidgetProvider {

    private final InvoiceRepository repo;

    public FacCliWidget(InvoiceRepository repo) { this.repo = repo; }

    @Override public String key() { return "FAC_CLI"; }
    @Override public String template() { return "intra/widgets/_multi"; }

    @Override
    public void populate(Model model, CustomUserDetails user) {
        model.addAttribute("subValues", List.of(
                new SubValue("À payer",   String.valueOf(repo.countByStatus(Invoice.InvoiceStatus.SENT)),    "#3b82f6"),
                new SubValue("En retard", String.valueOf(repo.countByStatus(Invoice.InvoiceStatus.OVERDUE)), "#dc2626"),
                new SubValue("Brouillon", String.valueOf(repo.countByStatus(Invoice.InvoiceStatus.DRAFT)),   "#94a3b8")
        ));
    }
}
