package com.logicielpourtous.site.features.commun;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/intra/settings/company")
public class IntraCompanyController {

    private final TenantRepository tenantRepository;
    private final CustomerRepository customerRepository;

    public IntraCompanyController(TenantRepository tenantRepository,
                                  CustomerRepository customerRepository) {
        this.tenantRepository = tenantRepository;
        this.customerRepository = customerRepository;
    }

    @GetMapping
    public String form(@AuthenticationPrincipal CustomUserDetails principal, Model model) {
        Tenant tenant = tenantRepository.findById(principal.getTenant().getId()).orElseThrow();
        model.addAttribute("tenant", tenant);
        model.addAttribute("allCustomers", customerRepository.findAll(
                org.springframework.data.domain.Sort.by("companyName")));
        return "intra/settings/company";
    }

    @PostMapping
    public String save(@AuthenticationPrincipal CustomUserDetails principal,
                       @RequestParam String name,
                       @RequestParam(required = false) String siren,
                       @RequestParam(required = false) String vatNumber,
                       @RequestParam(required = false) String phone,
                       @RequestParam(required = false) String email,
                       @RequestParam(required = false) String billingAddress,
                       @RequestParam(required = false) Long customerId,
                       RedirectAttributes ra) {
        Tenant tenant = tenantRepository.findById(principal.getTenant().getId()).orElseThrow();
        tenant.setName(name);
        tenant.setSiren(siren != null && siren.isBlank() ? null : siren);
        tenant.setVatNumber(vatNumber != null && vatNumber.isBlank() ? null : vatNumber);
        tenant.setPhone(phone != null && phone.isBlank() ? null : phone);
        tenant.setEmail(email != null && email.isBlank() ? null : email);
        tenant.setBillingAddress(billingAddress != null && billingAddress.isBlank() ? null : billingAddress);
        tenant.setCustomerId(customerId);
        tenantRepository.save(tenant);
        ra.addFlashAttribute("successMessage", "Informations entreprise enregistrées.");
        return "redirect:/intra/settings/company";
    }
}
