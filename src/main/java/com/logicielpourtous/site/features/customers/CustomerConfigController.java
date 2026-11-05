package com.logicielpourtous.site.features.customers;

import com.logicielpourtous.site.features.integration.ClientIntegration;
import com.logicielpourtous.site.features.integration.ClientIntegrationRepository;
import com.logicielpourtous.site.features.ninja.NinjaOneClient;
import com.logicielpourtous.site.features.ninja.NinjaOneConfig;
import com.logicielpourtous.site.features.ninja.NinjaOneConfigRepository;
import com.logicielpourtous.site.features.ninja.NinjaOrganization;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/intra/customers/config")
public class CustomerConfigController {

    private final CustomerRepository          customerRepository;
    private final ClientIntegrationRepository integrationRepository;
    private final NinjaOneConfigRepository    ninjaConfigRepository;
    private final NinjaOneClient              ninjaOneClient;

    public CustomerConfigController(CustomerRepository customerRepository,
                                    ClientIntegrationRepository integrationRepository,
                                    NinjaOneConfigRepository ninjaConfigRepository,
                                    NinjaOneClient ninjaOneClient) {
        this.customerRepository    = customerRepository;
        this.integrationRepository = integrationRepository;
        this.ninjaConfigRepository = ninjaConfigRepository;
        this.ninjaOneClient        = ninjaOneClient;
    }

    @GetMapping
    public String page(@RequestParam(required = false) Long customerId, Model model) {
        List<Customer> customers = customerRepository.findAll();
        model.addAttribute("customers", customers);
        model.addAttribute("serviceTypes", ClientIntegration.ServiceType.values());

        // Si aucun client sélectionné, prendre le premier disponible
        if (customerId == null && !customers.isEmpty()) {
            customerId = customers.get(0).getId();
        }

        if (customerId != null) {
            Customer customer = customerRepository.findById(customerId).orElse(null);
            if (customer != null) {
                model.addAttribute("customer", customer);
                model.addAttribute("integrations",
                        integrationRepository.findByCustomerIdOrderByServiceTypeAscLabelAsc(customerId));

                // NinjaOne orgs
                List<NinjaOrganization> ninjaOrgs = List.of();
                try {
                    NinjaOneConfig cfg = ninjaConfigRepository.findById(1L).orElse(null);
                    if (cfg != null && cfg.isEnabled()) {
                        ninjaOrgs = ninjaOneClient.getOrganizations(cfg);
                    }
                } catch (Exception ignored) {}
                model.addAttribute("ninjaOrgs", ninjaOrgs);
                model.addAttribute("selectedCustomerId", customerId);
            }
        }

        return "intra/customers/config";
    }

    // ── Intégrations ──────────────────────────────────────────────────────

    @PostMapping("/integrations")
    public String createIntegration(@RequestParam Long customerId,
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
        return "redirect:/intra/customers/config?customerId=" + customerId;
    }

    @PostMapping("/integrations/{id}/toggle")
    public String toggleIntegration(@PathVariable Long id,
                                    @RequestParam Long customerId,
                                    RedirectAttributes ra) {
        integrationRepository.findById(id).ifPresent(i -> {
            i.setEnabled(!i.isEnabled());
            integrationRepository.save(i);
        });
        return "redirect:/intra/customers/config?customerId=" + customerId;
    }

    @PostMapping("/integrations/{id}/delete")
    public String deleteIntegration(@PathVariable Long id,
                                    @RequestParam Long customerId,
                                    RedirectAttributes ra) {
        integrationRepository.deleteById(id);
        ra.addFlashAttribute("successMessage", "Intégration supprimée.");
        return "redirect:/intra/customers/config?customerId=" + customerId;
    }

    // ── NinjaOne org ──────────────────────────────────────────────────────

    @PostMapping("/ninja-org/link")
    public String linkNinjaOrg(@RequestParam Long customerId,
                               @RequestParam(required = false) Integer ninjaOrganizationId,
                               RedirectAttributes ra) {
        customerRepository.findById(customerId).ifPresent(c -> {
            c.setNinjaOrganizationId(ninjaOrganizationId);
            customerRepository.save(c);
        });
        ra.addFlashAttribute("successMessage",
                ninjaOrganizationId != null ? "Organisation NinjaOne liée." : "Lien NinjaOne supprimé.");
        return "redirect:/intra/customers/config?customerId=" + customerId;
    }

    @PostMapping("/ninja-org/create")
    public String createNinjaOrg(@RequestParam Long customerId, RedirectAttributes ra) {
        Customer customer = customerRepository.findById(customerId).orElseThrow();
        try {
            NinjaOneConfig cfg = ninjaConfigRepository.findById(1L).orElse(null);
            if (cfg == null || !cfg.isEnabled()) {
                ra.addFlashAttribute("errorMessage", "NinjaOne n'est pas configuré ou activé.");
                return "redirect:/intra/customers/config?customerId=" + customerId;
            }
            NinjaOrganization org = ninjaOneClient.createOrganization(cfg, customer.getCompanyName());
            if (org != null && org.getId() != null) {
                customer.setNinjaOrganizationId(org.getId());
                customerRepository.save(customer);
                ra.addFlashAttribute("successMessage",
                        "Organisation NinjaOne « " + org.getName() + " » créée et liée.");
            } else {
                ra.addFlashAttribute("errorMessage", "Échec de la création de l'organisation NinjaOne.");
            }
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", "Erreur NinjaOne : " + e.getMessage());
        }
        return "redirect:/intra/customers/config?customerId=" + customerId;
    }
}
