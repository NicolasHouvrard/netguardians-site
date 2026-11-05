package com.logicielpourtous.site.features.accounting;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.invoice.Invoice;
import com.logicielpourtous.site.features.invoice.InvoiceRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/intra/accounting/einvoicing")
public class QontoPdpController {

    private final QontoPdpService pdpService;
    private final InvoiceRepository invoiceRepo;

    public QontoPdpController(QontoPdpService pdpService, InvoiceRepository invoiceRepo) {
        this.pdpService = pdpService;
        this.invoiceRepo = invoiceRepo;
    }

    @PostMapping("/{invoiceId}/submit")
    public String submit(@PathVariable Long invoiceId,
                         @AuthenticationPrincipal CustomUserDetails principal,
                         RedirectAttributes ra) {
        Invoice invoice = invoiceRepo.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Facture introuvable"));

        QontoPdpService.PdpResult result = pdpService.submitInvoice(principal.getTenant(), invoice);

        if (result.success()) {
            ra.addFlashAttribute("successMessage", result.message());
        } else {
            ra.addFlashAttribute("errorMessage", result.message());
        }
        return "redirect:/intra/invoices/" + invoiceId;
    }

    @PostMapping("/{invoiceId}/check-status")
    public String checkStatus(@PathVariable Long invoiceId,
                              @AuthenticationPrincipal CustomUserDetails principal,
                              RedirectAttributes ra) {
        Invoice invoice = invoiceRepo.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Facture introuvable"));

        QontoPdpService.PdpResult result = pdpService.checkStatus(principal.getTenant(), invoice);

        if (result.success()) {
            ra.addFlashAttribute("successMessage", result.message());
        } else {
            ra.addFlashAttribute("errorMessage", result.message());
        }
        return "redirect:/intra/invoices/" + invoiceId;
    }
}
