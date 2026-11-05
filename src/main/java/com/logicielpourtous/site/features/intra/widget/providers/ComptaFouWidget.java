package com.logicielpourtous.site.features.intra.widget.providers;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.intra.widget.AmountBox;
import com.logicielpourtous.site.features.intra.widget.IntraWidgetProvider;
import com.logicielpourtous.site.features.intra.widget.StatusBadge;
import com.logicielpourtous.site.features.intra.widget.WidgetUtils;
import com.logicielpourtous.site.features.purchase.SupplierInvoice;
import com.logicielpourtous.site.features.purchase.SupplierInvoiceRepository;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.util.List;

@Component
public class ComptaFouWidget implements IntraWidgetProvider {

    private final SupplierInvoiceRepository repo;

    public ComptaFouWidget(SupplierInvoiceRepository repo) { this.repo = repo; }

    @Override public String key() { return "COMPTA_FOU"; }
    @Override public String template() { return "intra/widgets/_card-summary"; }

    @Override
    public void populate(Model model, CustomUserDetails user) {
        Tenant t = user != null ? user.getTenant() : null;

        if (t == null) {
            model.addAttribute("amountBoxes",  List.of(
                    new AmountBox("Dépenses réglées", "—", "warning"),
                    new AmountBox("À régler",         "—", "danger")
            ));
            model.addAttribute("statusBadges", List.of(
                    new StatusBadge("Brouillons", "—", "text-bg-secondary"),
                    new StatusBadge("À payer",    "—", "text-bg-light text-muted"),
                    new StatusBadge("Payées",     "—", "text-bg-success")
            ));
            return;
        }

        long toPay = repo.countByTenantAndStatus(t, SupplierInvoice.Status.TO_PAY);

        model.addAttribute("amountBoxes", List.of(
                new AmountBox("Dépenses réglées", WidgetUtils.formatAmount(repo.sumTotalTTCByTenantAndStatus(t, SupplierInvoice.Status.PAID)),   "warning"),
                new AmountBox("À régler",         WidgetUtils.formatAmount(repo.sumTotalTTCByTenantAndStatus(t, SupplierInvoice.Status.TO_PAY)), "danger")
        ));
        model.addAttribute("statusBadges", List.of(
                new StatusBadge("Brouillons", String.valueOf(repo.countByTenantAndStatus(t, SupplierInvoice.Status.DRAFT)), "text-bg-secondary"),
                new StatusBadge("À payer",    String.valueOf(toPay),
                        toPay > 0 ? "text-bg-warning text-dark" : "text-bg-light text-muted"),
                new StatusBadge("Payées",     String.valueOf(repo.countByTenantAndStatus(t, SupplierInvoice.Status.PAID)), "text-bg-success")
        ));
    }
}
