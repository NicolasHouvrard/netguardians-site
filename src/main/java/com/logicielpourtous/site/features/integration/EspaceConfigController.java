package com.logicielpourtous.site.features.integration;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/espace/config")
public class EspaceConfigController {

    private final ClientIntegrationRepository integrationRepository;
    private final CustomerRepository          customerRepository;

    public EspaceConfigController(ClientIntegrationRepository integrationRepository,
                                  CustomerRepository customerRepository) {
        this.integrationRepository = integrationRepository;
        this.customerRepository    = customerRepository;
    }

    @GetMapping
    public String list(@AuthenticationPrincipal CustomUserDetails current, Model model) {
        Long customerId = current.getCustomerId();
        if (customerId == null) return "redirect:/espace";

        Customer customer = customerRepository.findById(customerId).orElse(null);
        model.addAttribute("customer",     customer);
        model.addAttribute("integrations", integrationRepository
                .findByCustomerIdOrderByServiceTypeAscLabelAsc(customerId));
        model.addAttribute("serviceTypes", ClientIntegration.ServiceType.values());
        return "espace/config";
    }

    // GESTIONNAIRE can declare an integration (label + notes only) — credentials are set by ESN via /intra/integrations
    @PostMapping
    public String create(@AuthenticationPrincipal CustomUserDetails current,
                         @RequestParam ClientIntegration.ServiceType serviceType,
                         @RequestParam(required = false) String label,
                         @RequestParam(required = false) String notes,
                         RedirectAttributes ra) {
        Long customerId = current.getCustomerId();
        if (customerId == null) return "redirect:/espace";

        ClientIntegration i = new ClientIntegration();
        i.setCustomerId(customerId);
        i.setServiceType(serviceType);
        i.setLabel(label != null && !label.isBlank() ? label : serviceType.label);
        i.setNotes(notes);
        i.setEnabled(false); // ESN must configure and activate it
        integrationRepository.save(i);
        ra.addFlashAttribute("successMessage",
                "Demande d'intégration « " + i.getLabel() + " » envoyée. Votre ESN va la configurer.");
        return "redirect:/espace/config";
    }

    @PostMapping("/{id}/toggle")
    public String toggle(@PathVariable Long id,
                         @AuthenticationPrincipal CustomUserDetails current,
                         RedirectAttributes ra) {
        integrationRepository.findById(id).ifPresent(i -> {
            if (i.getCustomerId().equals(current.getCustomerId())) {
                i.setEnabled(!i.isEnabled());
                integrationRepository.save(i);
            }
        });
        return "redirect:/espace/config";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         @AuthenticationPrincipal CustomUserDetails current,
                         RedirectAttributes ra) {
        integrationRepository.findById(id).ifPresent(i -> {
            if (i.getCustomerId().equals(current.getCustomerId())) {
                integrationRepository.delete(i);
                ra.addFlashAttribute("successMessage", "Intégration supprimée.");
            }
        });
        return "redirect:/espace/config";
    }
}
