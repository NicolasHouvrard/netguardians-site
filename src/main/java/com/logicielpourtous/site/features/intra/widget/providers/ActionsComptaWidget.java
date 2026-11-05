package com.logicielpourtous.site.features.intra.widget.providers;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.intra.widget.IntraWidgetProvider;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import java.util.List;

@Component
public class ActionsComptaWidget implements IntraWidgetProvider {

    @Override public String key() { return "ACTIONS_COMPTA"; }
    @Override public String template() { return "intra/widgets/_links"; }

    @Override
    public void populate(Model model, CustomUserDetails user) {
        model.addAttribute("rows", List.of(
                List.of("fas fa-plus",         "Nouvelle facture client",     "/intra/invoices/new"),
                List.of("fas fa-upload",        "Importer fact. fournisseur",  "/intra/supplier-invoices/upload"),
                List.of("fas fa-pen-to-square", "Journal des écritures",       "/intra/accounting/entries"),
                List.of("fas fa-layer-group",   "Lots OD",                     "/intra/accounting/batches")
        ));
    }
}
