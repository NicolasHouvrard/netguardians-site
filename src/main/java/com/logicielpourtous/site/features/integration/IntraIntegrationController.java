package com.logicielpourtous.site.features.integration;

import com.logicielpourtous.site.features.customers.CustomerRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/intra/integrations")
public class IntraIntegrationController {

    private final ClientIntegrationRepository integrationRepository;
    private final CustomerRepository          customerRepository;

    public IntraIntegrationController(ClientIntegrationRepository integrationRepository,
                                      CustomerRepository customerRepository) {
        this.integrationRepository = integrationRepository;
        this.customerRepository    = customerRepository;
    }

    @GetMapping
    public String list(@RequestParam(required = false) Long customerId,
                       @RequestParam(required = false) String service,
                       Model model) {
        List<ClientIntegration> integrations;
        if (customerId != null) {
            integrations = integrationRepository.findByCustomerIdOrderByServiceTypeAscLabelAsc(customerId);
        } else {
            integrations = integrationRepository.findAllByOrderByCustomerIdAscServiceTypeAsc();
        }

        if (service != null && !service.isBlank()) {
            ClientIntegration.ServiceType st = ClientIntegration.ServiceType.valueOf(service);
            integrations = integrations.stream().filter(i -> i.getServiceType() == st).toList();
        }

        model.addAttribute("integrations",   integrations);
        model.addAttribute("customers",      customerRepository.findAll());
        model.addAttribute("serviceTypes",   ClientIntegration.ServiceType.values());
        model.addAttribute("filterCustomer", customerId);
        model.addAttribute("filterService",  service);
        return "intra/integrations/list";
    }

    @PostMapping
    public String create(@RequestParam Long customerId,
                         @RequestParam ClientIntegration.ServiceType serviceType,
                         @RequestParam(required = false) String label,
                         @RequestParam(required = false) String apiKey,
                         @RequestParam(required = false) String clientId,
                         @RequestParam(required = false) String clientSecret,
                         @RequestParam(required = false) String tenantId,
                         @RequestParam(required = false) String webhookUrl,
                         @RequestParam(required = false) String notes,
                         RedirectAttributes ra) {

        ClientIntegration i = new ClientIntegration();
        i.setCustomerId(customerId);
        i.setServiceType(serviceType);
        i.setLabel(label != null && !label.isBlank() ? label : serviceType.label);
        i.setApiKey(apiKey);
        i.setClientId(clientId);
        i.setClientSecret(clientSecret);
        i.setTenantId(tenantId);
        i.setWebhookUrl(webhookUrl);
        i.setNotes(notes);
        integrationRepository.save(i);

        ra.addFlashAttribute("successMessage", "Intégration « " + i.getLabel() + " » créée.");
        return "redirect:/intra/integrations?customerId=" + customerId;
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        ClientIntegration integration = integrationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Intégration introuvable"));
        model.addAttribute("integration",  integration);
        model.addAttribute("customers",    customerRepository.findAll());
        model.addAttribute("serviceTypes", ClientIntegration.ServiceType.values());
        return "intra/integrations/edit";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id,
                         @RequestParam(required = false) String label,
                         @RequestParam(required = false) String apiKey,
                         @RequestParam(required = false) String clientId,
                         @RequestParam(required = false) String clientSecret,
                         @RequestParam(required = false) String tenantId,
                         @RequestParam(required = false) String webhookUrl,
                         @RequestParam(required = false) String notes,
                         @RequestParam(defaultValue = "false") boolean enabled,
                         RedirectAttributes ra) {
        Long cid = integrationRepository.findById(id).map(i -> {
            i.setLabel(label);
            i.setApiKey(apiKey);
            i.setClientId(clientId);
            i.setClientSecret(clientSecret);
            i.setTenantId(tenantId);
            i.setWebhookUrl(webhookUrl);
            i.setNotes(notes);
            i.setEnabled(enabled);
            integrationRepository.save(i);
            return i.getCustomerId();
        }).orElse(null);

        ra.addFlashAttribute("successMessage", "Intégration mise à jour.");
        return "redirect:/intra/integrations" + (cid != null ? "?customerId=" + cid : "");
    }

    @PostMapping("/{id}/toggle")
    public String toggle(@PathVariable Long id, RedirectAttributes ra) {
        Long cid = integrationRepository.findById(id).map(i -> {
            i.setEnabled(!i.isEnabled());
            integrationRepository.save(i);
            return i.getCustomerId();
        }).orElse(null);
        return "redirect:/intra/integrations" + (cid != null ? "?customerId=" + cid : "");
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        Long cid = integrationRepository.findById(id).map(ClientIntegration::getCustomerId).orElse(null);
        integrationRepository.deleteById(id);
        ra.addFlashAttribute("successMessage", "Intégration supprimée.");
        return "redirect:/intra/integrations" + (cid != null ? "?customerId=" + cid : "");
    }
}
