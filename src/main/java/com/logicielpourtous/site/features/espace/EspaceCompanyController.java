package com.logicielpourtous.site.features.espace;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.customers.*;
import com.logicielpourtous.site.features.portal.PortalUserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/espace/company")
public class EspaceCompanyController {

    private final CustomerRepository   customerRepository;
    private final ContactRepository    contactRepository;
    private final SiteRepository       siteRepository;
    private final PortalUserService    portalUserService;
    private final EspaceContextService ctx;

    public EspaceCompanyController(CustomerRepository customerRepository,
                                   ContactRepository contactRepository,
                                   SiteRepository siteRepository,
                                   PortalUserService portalUserService,
                                   EspaceContextService ctx) {
        this.customerRepository = customerRepository;
        this.contactRepository  = contactRepository;
        this.siteRepository     = siteRepository;
        this.portalUserService  = portalUserService;
        this.ctx                = ctx;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public String company(@AuthenticationPrincipal CustomUserDetails userDetails,
                          HttpSession session, Model model) {
        Customer customer = ctx.loadCustomer(userDetails, session);
        customer.getContacts().size();
        customer.getSites().size();
        model.addAttribute("customer", customer);
        return "espace/company";
    }

    @PostMapping
    public String save(@AuthenticationPrincipal CustomUserDetails userDetails,
                       HttpSession session,
                       @RequestParam(required = false) String email,
                       @RequestParam(required = false) String phone,
                       @RequestParam(required = false) String address,
                       RedirectAttributes ra) {
        Customer customer = ctx.loadCustomer(userDetails, session);
        customer.setEmail(email);
        customer.setPhone(phone);
        customer.setAddress(address);
        customerRepository.save(customer);
        ra.addFlashAttribute("successMessage", "Informations mises à jour.");
        return "redirect:/espace/company";
    }

    // ── Contacts ──────────────────────────────────────────────────────────────

    @PostMapping("/contacts")
    public String addContact(@AuthenticationPrincipal CustomUserDetails userDetails,
                             HttpSession session,
                             @RequestParam String firstName,
                             @RequestParam String lastName,
                             @RequestParam(required = false) String email,
                             @RequestParam(required = false) String phone,
                             @RequestParam(required = false) String position,
                             RedirectAttributes ra) {
        Customer customer = ctx.loadCustomer(userDetails, session);
        Contact contact = new Contact();
        contact.setFirstName(firstName);
        contact.setLastName(lastName);
        contact.setEmail(email);
        contact.setPhone(phone);
        contact.setPosition(position);
        contact.setCustomer(customer);
        contactRepository.save(contact);

        portalUserService.createForContact(contact, customer.getId(), "CLIENT").ifPresent(r -> {
            contact.setUserId(r.user().getId());
            contactRepository.save(contact);
            ra.addFlashAttribute("successMessage",
                    firstName + " " + lastName + " ajouté aux contacts et à l'équipe"
                    + " — mot de passe temporaire : " + r.tempPassword());
        });
        if (ra.getFlashAttributes().isEmpty()) {
            ra.addFlashAttribute("successMessage", firstName + " " + lastName + " ajouté aux contacts.");
        }
        return "redirect:/espace/company";
    }

    @PostMapping("/contacts/{cid}/update")
    public String updateContact(@PathVariable Long cid,
                                @AuthenticationPrincipal CustomUserDetails userDetails,
                                HttpSession session,
                                @RequestParam String firstName,
                                @RequestParam String lastName,
                                @RequestParam(required = false) String email,
                                @RequestParam(required = false) String phone,
                                @RequestParam(required = false) String position,
                                RedirectAttributes ra) {
        Customer customer = ctx.loadCustomer(userDetails, session);
        Contact contact = contactRepository.findById(cid)
                .filter(c -> customer.getId().equals(c.getCustomer().getId()))
                .orElseThrow(() -> new IllegalArgumentException("Contact introuvable"));
        contact.setFirstName(firstName);
        contact.setLastName(lastName);
        contact.setEmail(email != null && !email.isBlank() ? email : null);
        contact.setPhone(phone != null && !phone.isBlank() ? phone : null);
        contact.setPosition(position != null && !position.isBlank() ? position : null);
        contactRepository.save(contact);
        ra.addFlashAttribute("successMessage", firstName + " " + lastName + " mis à jour.");
        return "redirect:/espace/company";
    }

    @PostMapping("/contacts/{cid}/delete")
    public String deleteContact(@PathVariable Long cid,
                                @AuthenticationPrincipal CustomUserDetails userDetails,
                                HttpSession session,
                                RedirectAttributes ra) {
        Customer customer = ctx.loadCustomer(userDetails, session);
        Contact contact = contactRepository.findById(cid)
                .filter(c -> customer.getId().equals(c.getCustomer().getId()))
                .orElseThrow(() -> new IllegalArgumentException("Contact introuvable"));
        contactRepository.delete(contact);
        ra.addFlashAttribute("successMessage", "Contact supprimé.");
        return "redirect:/espace/company";
    }

    // ── Sites ─────────────────────────────────────────────────────────────────

    @PostMapping("/sites")
    public String addSite(@AuthenticationPrincipal CustomUserDetails userDetails,
                          HttpSession session,
                          @RequestParam String name,
                          @RequestParam(required = false) String address,
                          @RequestParam(required = false) String city,
                          @RequestParam(required = false) String zipCode,
                          RedirectAttributes ra) {
        Customer customer = ctx.loadCustomer(userDetails, session);
        Site site = new Site();
        site.setName(name);
        site.setAddress(address);
        site.setCity(city);
        site.setZipCode(zipCode);
        site.setCustomer(customer);
        siteRepository.save(site);
        ra.addFlashAttribute("successMessage", "Site « " + name + " » ajouté.");
        return "redirect:/espace/company";
    }

    @PostMapping("/sites/{sid}/delete")
    public String deleteSite(@PathVariable Long sid,
                             @AuthenticationPrincipal CustomUserDetails userDetails,
                             HttpSession session,
                             RedirectAttributes ra) {
        Customer customer = ctx.loadCustomer(userDetails, session);
        Site site = siteRepository.findById(sid)
                .filter(s -> customer.getId().equals(s.getCustomer().getId()))
                .orElseThrow(() -> new IllegalArgumentException("Site introuvable"));
        siteRepository.delete(site);
        ra.addFlashAttribute("successMessage", "Site supprimé.");
        return "redirect:/espace/company";
    }
}
