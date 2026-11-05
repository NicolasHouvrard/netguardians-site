package com.logicielpourtous.site.features.request;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.logicielpourtous.site.features.customers.ContactRepository;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.customers.SiteRepository;

@Controller
@RequestMapping("/admin/request-manager")
public class RequestManagerController {

    private final RequestRepository    requestRepository;
    private final CustomerRepository   customerRepository;
    private final ContactRepository    contactRepository;
    private final SiteRepository       siteRepository;

    public RequestManagerController(RequestRepository requestRepository,
                                    CustomerRepository customerRepository,
                                    ContactRepository contactRepository,
                                    SiteRepository siteRepository) {
        this.requestRepository  = requestRepository;
        this.customerRepository = customerRepository;
        this.contactRepository  = contactRepository;
        this.siteRepository     = siteRepository;
    }

    // ── Pages ────────────────────────────────────────────────────────────

    @GetMapping("/all")
    public String listAllRequests(Model model) {
        model.addAttribute("requests",
                requestRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt")));
        return "feature/manager/requests-list";
    }

    @GetMapping("/create")
    public String createForm(Model model) {
        Request request = new Request();
        request.setPriority(RequestPriority.MEDIUM);
        request.setStatus(RequestStatus.NEW);
        model.addAttribute("request", request);
        model.addAttribute("customers",      customerRepository.findAll(Sort.by("companyName")));
        model.addAttribute("customerSites",  List.of());
        model.addAttribute("contacts",       List.of());
        model.addAttribute("requestTypes",   RequestType.values());
        return "feature/manager/request-create-form";
    }

    @PostMapping("/save")
    public String save(@ModelAttribute Request request,
                       @RequestParam(required = false) Long siteId,
                       @RequestParam(required = false) Long contactId,
                       @RequestParam(required = false, defaultValue = "0") Integer estimatedHoursInput,
                       @RequestParam(required = false, defaultValue = "0") Integer estimatedMinsInput,
                       RedirectAttributes attrs) {
        if (siteId != null) {
            siteRepository.findById(siteId).ifPresent(request::setSite);
        }
        if (contactId != null) {
            contactRepository.findById(contactId).ifPresent(c -> {
                request.setContact(c);
                if (c.getSite() != null && siteId == null) request.setSite(c.getSite());
            });
        }
        int totalEstimated = estimatedHoursInput * 60 + estimatedMinsInput;
        request.setEstimatedMinutes(totalEstimated > 0 ? totalEstimated : null);

        request.registerChange();
        Request saved = requestRepository.save(request);
        attrs.addFlashAttribute("message", "Demande créée avec succès !");
        return "redirect:/intra/customers/" + saved.getCustomer().getId()
                + "/requests/edit/" + saved.getId();
    }

    // ── Fragments HTMX ───────────────────────────────────────────────────

    /** Options du select Client filtrées par q */
    @GetMapping("/customer-options")
    public String customerOptions(@RequestParam(defaultValue = "") String q,
                                  Model model) {
        model.addAttribute("customers",
                q.isBlank()
                        ? customerRepository.findAll(Sort.by("companyName"))
                        : customerRepository.findByCompanyNameContainingIgnoreCase(q));
        return "feature/requests/_fragments :: customer-options";
    }

    /** Options du select Site pour un client donné, filtrées par q.
     *  Le select client porte name="customer.id" → Spring reçoit le param "customer.id" */
    @GetMapping("/site-options")
    public String siteOptions(@RequestParam(name = "customer.id") Long customerId,
                              @RequestParam(defaultValue = "") String q,
                              Model model) {
        model.addAttribute("customerSites",
                siteRepository.findByCustomerIdAndNameContainingIgnoreCase(customerId, q));
        return "feature/requests/_fragments :: site-options";
    }

    /** Options du select Contact pour un client/site donnés, filtrées par q */
    @GetMapping("/contact-options")
    public String contactOptions(@RequestParam(name = "customer.id") Long customerId,
                                 @RequestParam(required = false) Long siteId,
                                 @RequestParam(defaultValue = "") String q,
                                 Model model) {
        model.addAttribute("contacts",
                contactRepository.search(customerId, siteId, q));
        return "feature/requests/_fragments :: contact-options";
    }

    /** Carte de détail d'un contact */
    @GetMapping("/contact-detail")
    public String contactDetail(@RequestParam(required = false) Long contactId,
                                Model model) {
        if (contactId != null) {
            contactRepository.findById(contactId)
                    .ifPresent(c -> model.addAttribute("selectedContact", c));
        }
        return "feature/requests/_fragments :: contact-detail";
    }
}
