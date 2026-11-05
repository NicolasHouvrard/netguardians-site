package com.logicielpourtous.site.features.customers;

import com.logicielpourtous.site.features.portal.PortalUserService;
import com.logicielpourtous.site.features.user.UserRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletResponse;

@Controller
public class ContactController {

    private final ContactRepository contactRepository;
    private final CustomerRepository customerRepository;
    private final SiteRepository siteRepository;
    private final PortalUserService portalUserService;
    private final UserRepository userRepository;

    public ContactController(ContactRepository contactRepository,
                             CustomerRepository customerRepository,
                             SiteRepository siteRepository,
                             PortalUserService portalUserService,
                             UserRepository userRepository) {
        this.contactRepository  = contactRepository;
        this.customerRepository = customerRepository;
        this.siteRepository     = siteRepository;
        this.portalUserService  = portalUserService;
        this.userRepository     = userRepository;
    }

    // -------------------------------------------------------
    // Contacts depuis l'onglet "Contacts" de la fiche client
    // -------------------------------------------------------

    @GetMapping("/intra/customers/{customerId}/contacts/add")
    public String showAddForm(@PathVariable Long customerId, Model model) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Client invalide"));
        Contact contact = new Contact();
        contact.setCustomer(customer);
        model.addAttribute("contact", contact);
        model.addAttribute("customer", customer);
        model.addAttribute("customerSites", siteRepository.findByCustomerId(customerId));
        return "feature/contacts/form";
    }

    @PostMapping("/intra/customers/{customerId}/contacts/save")
    public String save(@PathVariable Long customerId,
                       @RequestParam(required = false) Long selectedSiteId,
                       @RequestParam(defaultValue = "false") boolean esnAccess,
                       @ModelAttribute Contact contact,
                       RedirectAttributes redirectAttributes) {
        Customer customer = customerRepository.findById(customerId).get();
        contact.setCustomer(customer);
        if (selectedSiteId != null) {
            siteRepository.findById(selectedSiteId).ifPresent(contact::setSite);
        }
        contactRepository.save(contact);

        String[] roles = esnAccess
                ? new String[]{"CLIENT", "ROLE_USER"}
                : new String[]{"CLIENT"};

        portalUserService.createForContact(contact, customerId, roles).ifPresent(r -> {
            contact.setUserId(r.user().getId());
            contactRepository.save(contact);
            String msg = "Compte portail créé pour " + contact.getEmail()
                    + " — mot de passe temporaire : " + r.tempPassword();
            if (esnAccess) msg += " (accès ESN activé)";
            redirectAttributes.addFlashAttribute("successMessage", msg);
        });

        return "redirect:/intra/customers/edit/" + customerId + "#contacts-pane";
    }

    @PostMapping("/intra/customers/{customerId}/contacts/{contactId}/update")
    public String updateContact(@PathVariable Long customerId,
                                @PathVariable Long contactId,
                                @RequestParam String firstName,
                                @RequestParam String lastName,
                                @RequestParam(required = false) String email,
                                @RequestParam(required = false) String phone,
                                @RequestParam(required = false) String position,
                                @RequestParam(required = false) Long selectedSiteId,
                                RedirectAttributes redirectAttributes) {
        Contact contact = contactRepository.findById(contactId).orElseThrow();
        contact.setFirstName(firstName);
        contact.setLastName(lastName);
        String cleanEmail = email != null && !email.isBlank() ? email : null;
        contact.setEmail(cleanEmail);
        contact.setPhone(phone != null && !phone.isBlank() ? phone : null);
        contact.setPosition(position != null && !position.isBlank() ? position : null);
        if (selectedSiteId != null) {
            siteRepository.findById(selectedSiteId).ifPresent(contact::setSite);
        } else {
            contact.setSite(null);
        }
        if (contact.getUserId() != null) {
            userRepository.findById(contact.getUserId()).ifPresent(u -> {
                u.setFullName(firstName + " " + lastName);
                if (cleanEmail != null) u.setEmail(cleanEmail);
                userRepository.save(u);
            });
        }
        contactRepository.save(contact);
        redirectAttributes.addFlashAttribute("successMessage", firstName + " " + lastName + " mis à jour.");
        return "redirect:/intra/customers/" + customerId;
    }

    @GetMapping("/intra/customers/{customerId}/contacts/edit/{contactId}")
    public String showEditForm(@PathVariable Long customerId,
                               @PathVariable Long contactId,
                               Model model) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Client invalide"));
        Contact contact = contactRepository.findById(contactId)
                .orElseThrow(() -> new IllegalArgumentException("Contact invalide"));
        model.addAttribute("contact", contact);
        model.addAttribute("customer", customer);
        model.addAttribute("customerSites", siteRepository.findByCustomerId(customerId));
        return "feature/contacts/form";
    }

    // -------------------------------------------------------
    // Quick-add depuis une modale (formulaire demande)
    // -------------------------------------------------------

    @GetMapping("/intra/customers/{customerId}/contacts/quick-form")
    public String quickForm(@PathVariable Long customerId, Model model) {
        model.addAttribute("customerId", customerId);
        model.addAttribute("customerSites", siteRepository.findByCustomerId(customerId));
        return "feature/customers/contacts/_quick-form :: form";
    }

    @PostMapping("/intra/customers/{customerId}/contacts/quick-save")
    public String quickSave(@PathVariable Long customerId,
                            @RequestParam(required = false) Long selectedSiteId,
                            @ModelAttribute Contact contact,
                            Model model,
                            HttpServletResponse response) {
        Customer customer = customerRepository.findById(customerId).get();
        contact.setCustomer(customer);
        if (selectedSiteId != null) {
            siteRepository.findById(selectedSiteId).ifPresent(contact::setSite);
        }
        contactRepository.save(contact);

        // HX-Trigger-After-Settle : le JS s'exécute APRÈS que le OOB swap a posé l'option dans le DOM
        String trigger = String.format(
            "{\"contactAdded\":{\"id\":%d,\"name\":\"%s\",\"position\":\"%s\",\"email\":\"%s\",\"phone\":\"%s\",\"siteId\":%s,\"siteName\":\"%s\"}}",
            contact.getId(),
            escape(contact.getFirstName() + " " + contact.getLastName()),
            escape(contact.getPosition()),
            escape(contact.getEmail()),
            escape(contact.getPhone()),
            contact.getSite() != null ? contact.getSite().getId() : "null",
            contact.getSite() != null ? escape(contact.getSite().getName()) : ""
        );
        response.setHeader("HX-Trigger-After-Settle", trigger);

        model.addAttribute("contact", contact);
        return "feature/customers/contacts/_quick-form :: new-option";
    }

    private String escape(String s) {
        return s != null ? s.replace("\"", "\\\"") : "";
    }

    @GetMapping("/intra/customers/{customerId}/contacts/delete/{contactId}")
    public String delete(@PathVariable Long customerId,
                         @PathVariable Long contactId,
                         RedirectAttributes redirectAttributes) {
        contactRepository.deleteById(contactId);
        redirectAttributes.addFlashAttribute("message", "Contact supprimé avec succès !");
        return "redirect:/intra/customers/edit/" + customerId + "#contacts-pane";
    }

    // -------------------------------------------------------
    // Contacts rattachés à un site spécifique
    // -------------------------------------------------------

    @GetMapping("/intra/customers/{customerId}/sites/{siteId}/contacts")
    public String listSiteContacts(@PathVariable Long customerId,
                                   @PathVariable Long siteId,
                                   Model model) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Client invalide"));
        Site site = siteRepository.findByIdWithContacts(siteId)
                .orElseThrow(() -> new IllegalArgumentException("Site invalide"));
        model.addAttribute("customer", customer);
        model.addAttribute("site", site);
        return "feature/customers/sites/contacts";
    }

    @GetMapping("/intra/customers/{customerId}/sites/{siteId}/contacts/new")
    public String newSiteContactForm(@PathVariable Long customerId,
                                     @PathVariable Long siteId,
                                     Model model) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Client invalide"));
        Site site = siteRepository.findById(siteId)
                .orElseThrow(() -> new IllegalArgumentException("Site invalide"));
        Contact contact = new Contact();
        contact.setCustomer(customer);
        contact.setSite(site);
        model.addAttribute("contact", contact);
        model.addAttribute("customer", customer);
        model.addAttribute("site", site);
        model.addAttribute("customerSites", siteRepository.findByCustomerId(customerId));
        return "feature/customers/sites/contact-form";
    }

    @PostMapping("/intra/customers/{customerId}/sites/{siteId}/contacts/save")
    public String saveSiteContact(@PathVariable Long customerId,
                                  @PathVariable Long siteId,
                                  @RequestParam(required = false) Long selectedSiteId,
                                  @RequestParam(defaultValue = "false") boolean esnAccess,
                                  @ModelAttribute Contact contact,
                                  RedirectAttributes redirectAttributes) {
        Customer customer = customerRepository.findById(customerId).get();
        Long targetSiteId = (selectedSiteId != null) ? selectedSiteId : siteId;
        Site site = siteRepository.findById(targetSiteId).get();
        contact.setCustomer(customer);
        contact.setSite(site);
        contactRepository.save(contact);

        String[] roles = esnAccess
                ? new String[]{"CLIENT", "ROLE_USER"}
                : new String[]{"CLIENT"};

        portalUserService.createForContact(contact, customerId, roles).ifPresent(r -> {
            contact.setUserId(r.user().getId());
            contactRepository.save(contact);
            String msg = "Compte portail créé pour " + contact.getEmail()
                    + " — mot de passe temporaire : " + r.tempPassword();
            if (esnAccess) msg += " (accès ESN activé)";
            redirectAttributes.addFlashAttribute("successMessage", msg);
        });
        return "redirect:/intra/customers/" + customerId + "/sites/" + targetSiteId + "/contacts";
    }

    @GetMapping("/intra/customers/{customerId}/sites/{siteId}/contacts/edit/{contactId}")
    public String editSiteContactForm(@PathVariable Long customerId,
                                      @PathVariable Long siteId,
                                      @PathVariable Long contactId,
                                      Model model) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Client invalide"));
        Site site = siteRepository.findById(siteId)
                .orElseThrow(() -> new IllegalArgumentException("Site invalide"));
        Contact contact = contactRepository.findById(contactId)
                .orElseThrow(() -> new IllegalArgumentException("Contact invalide"));
        model.addAttribute("contact", contact);
        model.addAttribute("customer", customer);
        model.addAttribute("site", site);
        model.addAttribute("customerSites", siteRepository.findByCustomerId(customerId));
        return "feature/customers/sites/contact-form";
    }

    @GetMapping("/intra/customers/{customerId}/sites/{siteId}/contacts/delete/{contactId}")
    public String deleteSiteContact(@PathVariable Long customerId,
                                    @PathVariable Long siteId,
                                    @PathVariable Long contactId,
                                    RedirectAttributes redirectAttributes) {
        contactRepository.deleteById(contactId);
        redirectAttributes.addFlashAttribute("message", "Contact supprimé.");
        return "redirect:/intra/customers/" + customerId + "/sites/" + siteId + "/contacts";
    }
}
