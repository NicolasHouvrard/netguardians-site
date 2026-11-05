package com.logicielpourtous.site.features.intra.widget.providers;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.intra.widget.IntraWidgetProvider;
import com.logicielpourtous.site.features.invoice.Invoice;
import com.logicielpourtous.site.features.invoice.InvoiceRepository;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

@Component
public class PendingInvoicesWidget implements IntraWidgetProvider {

    private final InvoiceRepository repo;

    public PendingInvoicesWidget(InvoiceRepository repo) { this.repo = repo; }

    @Override public String key() { return "PENDING_INVOICES"; }

    @Override
    public void populate(Model model, CustomUserDetails user) {
        model.addAttribute("value", String.valueOf(repo.countByStatus(Invoice.InvoiceStatus.SENT)));
    }
}
