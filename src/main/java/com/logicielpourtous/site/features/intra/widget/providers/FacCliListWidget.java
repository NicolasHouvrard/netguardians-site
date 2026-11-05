package com.logicielpourtous.site.features.intra.widget.providers;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.intra.widget.IntraWidgetProvider;
import com.logicielpourtous.site.features.intra.widget.WidgetUtils;
import com.logicielpourtous.site.features.invoice.InvoiceRepository;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class FacCliListWidget implements IntraWidgetProvider {

    private final InvoiceRepository repo;

    public FacCliListWidget(InvoiceRepository repo) { this.repo = repo; }

    @Override public String key() { return "FAC_CLI_LIST"; }
    @Override public String template() { return "intra/widgets/_list"; }

    @Override
    public void populate(Model model, CustomUserDetails user) {
        List<List<String>> rows = repo.findTop10ByOrderByCreatedAtDesc().stream()
                .map(inv -> List.of(
                        inv.getInvoiceNumber() != null ? inv.getInvoiceNumber() : "—",
                        inv.getCustomer()      != null ? inv.getCustomer().getCompanyName() : "—",
                        WidgetUtils.formatAmount(inv.getTotalTTC()),
                        inv.getStatus().label
                ))
                .collect(Collectors.toList());
        model.addAttribute("rows", rows);
        model.addAttribute("headers", List.of("N°", "Client", "Montant", "Statut"));
    }
}
