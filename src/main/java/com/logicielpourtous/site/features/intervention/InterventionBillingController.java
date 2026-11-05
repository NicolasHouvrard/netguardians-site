package com.logicielpourtous.site.features.intervention;

import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.invoice.Invoice;
import com.logicielpourtous.site.features.invoice.InvoiceRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;

@Controller
@RequestMapping("/intra/interventions/billing")
public class InterventionBillingController {

    private final InterventionRepository interventionRepository;
    private final InterventionController interventionController;
    private final CustomerRepository     customerRepository;
    private final InvoiceRepository      invoiceRepository;

    public InterventionBillingController(InterventionRepository interventionRepository,
                                         InterventionController interventionController,
                                         CustomerRepository customerRepository,
                                         InvoiceRepository invoiceRepository) {
        this.interventionRepository = interventionRepository;
        this.interventionController = interventionController;
        this.customerRepository     = customerRepository;
        this.invoiceRepository      = invoiceRepository;
    }

    @GetMapping
    public String billing(@RequestParam(required = false) Long customerId,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                          Model model) {
        List<Intervention> billable = interventionRepository.findBillable(customerId, from, to);
        model.addAttribute("billable",   billable);
        model.addAttribute("customers",  customerRepository.findAll());
        model.addAttribute("customerId", customerId);
        model.addAttribute("from",       from);
        model.addAttribute("to",         to);
        return "intra/interventions/billing";
    }

    @PostMapping("/generate")
    @Transactional
    public String generate(@RequestParam List<Long> interventionIds,
                           @RequestParam(defaultValue = "false") boolean perIntervention,
                           RedirectAttributes ra) {
        if (interventionIds == null || interventionIds.isEmpty()) {
            ra.addFlashAttribute("errorMessage", "Aucune intervention sélectionnée.");
            return "redirect:/intra/interventions/billing";
        }

        List<Intervention> list = interventionRepository.findAllById(interventionIds)
                .stream()
                .filter(i -> i.getStatus() == Intervention.InterventionStatus.COMPLETED
                        && i.getLinkedInvoice() == null)
                .toList();

        if (list.isEmpty()) {
            ra.addFlashAttribute("errorMessage", "Les interventions sélectionnées sont déjà facturées.");
            return "redirect:/intra/interventions/billing";
        }

        if (perIntervention) {
            // Une facture par intervention
            int count = 0;
            for (Intervention i : list) {
                Invoice invoice = interventionController.buildInvoice(List.of(i));
                invoiceRepository.save(invoice);
                invoice.setInvoiceNumber("FAC-" + LocalDate.now().getYear() + "-" + invoice.getId());
                invoiceRepository.save(invoice);
                i.setStatus(Intervention.InterventionStatus.INVOICED);
                i.setLinkedInvoice(invoice);
                interventionRepository.save(i);
                count++;
            }
            ra.addFlashAttribute("successMessage", count + " facture(s) créée(s).");
        } else {
            // Une seule facture regroupant toutes les interventions
            Invoice invoice = interventionController.buildInvoice(list);
            invoiceRepository.save(invoice);
            invoice.setInvoiceNumber("FAC-" + LocalDate.now().getYear() + "-" + invoice.getId());
            invoiceRepository.save(invoice);
            for (Intervention i : list) {
                i.setStatus(Intervention.InterventionStatus.INVOICED);
                i.setLinkedInvoice(invoice);
                interventionRepository.save(i);
            }
            ra.addFlashAttribute("successMessage",
                    "Facture " + invoice.getInvoiceNumber() + " créée (" + list.size() + " interventions).");
            return "redirect:/intra/invoices/" + invoice.getId();
        }

        return "redirect:/intra/invoices";
    }
}
