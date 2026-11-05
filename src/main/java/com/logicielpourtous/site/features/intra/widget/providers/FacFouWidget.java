package com.logicielpourtous.site.features.intra.widget.providers;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.intra.widget.IntraWidgetProvider;
import com.logicielpourtous.site.features.intra.widget.SubValue;
import com.logicielpourtous.site.features.purchase.SupplierInvoice;
import com.logicielpourtous.site.features.purchase.SupplierInvoiceRepository;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.time.LocalDate;
import java.util.List;

@Component
public class FacFouWidget implements IntraWidgetProvider {

    private final SupplierInvoiceRepository repo;

    public FacFouWidget(SupplierInvoiceRepository repo) { this.repo = repo; }

    @Override public String key() { return "FAC_FOU"; }
    @Override public String template() { return "intra/widgets/_multi"; }

    @Override
    public void populate(Model model, CustomUserDetails user) {
        Tenant t = user != null ? user.getTenant() : null;
        List<SubValue> subs;
        if (t == null) {
            subs = List.of(
                    new SubValue("À payer",   "—", "#f59e0b"),
                    new SubValue("En retard", "—", "#ef4444"),
                    new SubValue("Brouillon", "—", "#64748b"));
        } else {
            subs = List.of(
                    new SubValue("À payer",   String.valueOf(repo.countByTenantAndStatus(t, SupplierInvoice.Status.TO_PAY)),  "#f59e0b"),
                    new SubValue("En retard", String.valueOf(repo.countByTenantAndStatusAndDueDateBefore(t, SupplierInvoice.Status.TO_PAY, LocalDate.now())), "#ef4444"),
                    new SubValue("Brouillon", String.valueOf(repo.countByTenantAndStatus(t, SupplierInvoice.Status.DRAFT)),   "#64748b")
            );
        }
        model.addAttribute("subValues", subs);
    }
}
