package com.logicielpourtous.site.features.intra.widget.providers;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.intra.widget.AmountBox;
import com.logicielpourtous.site.features.intra.widget.IntraWidgetProvider;
import com.logicielpourtous.site.features.intra.widget.StatusBadge;
import com.logicielpourtous.site.features.intra.widget.WidgetUtils;
import com.logicielpourtous.site.features.invoice.Invoice;
import com.logicielpourtous.site.features.invoice.InvoiceRepository;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.util.List;

@Component
public class ComptaCliWidget implements IntraWidgetProvider {

    private final InvoiceRepository repo;

    public ComptaCliWidget(InvoiceRepository repo) { this.repo = repo; }

    @Override public String key() { return "COMPTA_CLI"; }
    @Override public String template() { return "intra/widgets/_card-summary"; }

    @Override
    public void populate(Model model, CustomUserDetails user) {
        long overdue = repo.countByStatus(Invoice.InvoiceStatus.OVERDUE);

        model.addAttribute("amountBoxes", List.of(
                new AmountBox("CA encaissé",       WidgetUtils.formatAmount(repo.sumPaidSales()), "success"),
                new AmountBox("Impayés en retard",  WidgetUtils.formatAmount(repo.sumOverdue()),  "danger")
        ));
        model.addAttribute("statusBadges", List.of(
                new StatusBadge("Brouillons", String.valueOf(repo.countByStatus(Invoice.InvoiceStatus.DRAFT)),   "text-bg-secondary"),
                new StatusBadge("Envoyées",   String.valueOf(repo.countByStatus(Invoice.InvoiceStatus.SENT)),    "text-bg-info"),
                new StatusBadge("Réglées",    String.valueOf(repo.countByStatus(Invoice.InvoiceStatus.PAID)),    "text-bg-success"),
                new StatusBadge("En retard",  String.valueOf(overdue),
                        overdue > 0 ? "text-bg-danger" : "text-bg-light text-muted")
        ));
    }
}
