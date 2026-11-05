package com.logicielpourtous.site.features.customers;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletResponse;

@Controller
@RequestMapping("/intra/customers/{customerId}/sites")
public class SiteController {

    private final SiteRepository siteRepository;
    private final CustomerRepository customerRepository;

    public SiteController(SiteRepository siteRepository, CustomerRepository customerRepository) {
        this.siteRepository = siteRepository;
        this.customerRepository = customerRepository;
    }

    // -------------------------------------------------------
    // Quick-add depuis une modale (formulaire demande)
    // -------------------------------------------------------

    @GetMapping("/quick-form")
    public String quickForm(@PathVariable Long customerId, Model model) {
        model.addAttribute("site", new Site());
        model.addAttribute("customerId", customerId);
        return "feature/customers/sites/_quick-form :: form";
    }

    @PostMapping("/quick-save")
    public String quickSave(@PathVariable Long customerId,
                            @ModelAttribute Site site,
                            Model model,
                            HttpServletResponse response) {
        Customer customer = customerRepository.findById(customerId).get();
        site.setCustomer(customer);
        siteRepository.save(site);

        String trigger = String.format(
            "{\"siteAdded\":{\"id\":%d,\"name\":\"%s\"}}",
            site.getId(),
            site.getName() != null ? site.getName().replace("\"", "\\\"") : ""
        );
        response.setHeader("HX-Trigger-After-Settle", trigger);

        model.addAttribute("site", site);
        return "feature/customers/sites/_quick-form :: new-option";
    }

    // Formulaire nouveau site — retourne un fragment HTMX dans la modale
    @GetMapping("/new")
    public String newForm(@PathVariable Long customerId, Model model) {
        model.addAttribute("site", new Site());
        model.addAttribute("customerId", customerId);
        return "feature/customers/sites/form :: form";
    }

    // Formulaire édition — retourne un fragment HTMX dans la modale
    @GetMapping("/edit/{id}")
    public String edit(@PathVariable Long customerId,
                       @PathVariable Long id,
                       Model model) {
        Site site = siteRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Site introuvable"));
        model.addAttribute("site", site);
        model.addAttribute("customerId", customerId);
        return "feature/customers/sites/form :: form";
    }

    // Sauvegarde (création ou modification) — retourne le fragment liste mis à jour
    @PostMapping("/save")
    public String save(@PathVariable Long customerId,
                       @ModelAttribute Site site,
                       Model model,
                       @RequestHeader(value = "HX-Request", required = false) String hxRequest) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Customer introuvable"));
        site.setCustomer(customer);
        siteRepository.save(site);

        // Recharge le customer pour avoir la liste sites à jour
        customer = customerRepository.findById(customerId).get();
        model.addAttribute("customer", customer);

        if (hxRequest != null) {
            return "feature/customers/sites/_fragments :: sites-list";
        }
        return "redirect:/intra/customers/edit/" + customerId;
    }

    // Suppression — retourne le fragment liste mis à jour
    @DeleteMapping("/delete/{id}")
    public String delete(@PathVariable Long customerId,
                         @PathVariable Long id,
                         Model model) {
        siteRepository.deleteById(id);

        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Customer introuvable"));
        model.addAttribute("customer", customer);
        return "feature/customers/sites/_fragments :: sites-list";
    }
}
