package com.logicielpourtous.site.features.intra.widget.providers;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.intra.widget.IntraWidgetProvider;
import com.logicielpourtous.site.features.intra.widget.WidgetUtils;
import com.logicielpourtous.site.features.purchase.SupplierInvoiceRepository;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class FacFouListWidget implements IntraWidgetProvider {

    private final SupplierInvoiceRepository repo;

    public FacFouListWidget(SupplierInvoiceRepository repo) { this.repo = repo; }

    @Override public String key() { return "FAC_FOU_LIST"; }
    @Override public String template() { return "intra/widgets/_list"; }

    @Override
    public void populate(Model model, CustomUserDetails user) {
        Tenant t = user != null ? user.getTenant() : null;
        List<List<String>> rows = t == null ? List.of() :
                repo.findTop10ByTenantOrderByCreatedAtDesc(t).stream()
                        .map(inv -> List.of(
                                inv.getInvoiceNumber() != null ? inv.getInvoiceNumber() : "—",
                                inv.getVendorName()    != null ? inv.getVendorName()    : "—",
                                WidgetUtils.formatAmount(inv.getTotalTTC()),
                                inv.getStatus().getLabel()
                        ))
                        .collect(Collectors.toList());
        model.addAttribute("rows", rows);
        model.addAttribute("headers", List.of("N°", "Fournisseur", "Montant", "Statut"));
    }
}
