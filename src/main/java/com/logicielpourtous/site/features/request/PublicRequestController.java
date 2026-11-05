package com.logicielpourtous.site.features.request;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.customers.Contact;
import com.logicielpourtous.site.features.customers.ContactRepository;
import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.customers.ProspectStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/public/demande")
public class PublicRequestController {

    private final RequestRepository  requestRepository;
    private final ContactRepository  contactRepository;
    private final CustomerRepository customerRepository;

    public PublicRequestController(RequestRepository requestRepository,
                                   ContactRepository contactRepository,
                                   CustomerRepository customerRepository) {
        this.requestRepository  = requestRepository;
        this.contactRepository  = contactRepository;
        this.customerRepository = customerRepository;
    }

    @GetMapping
    public String showForm(Authentication auth, Model model) {
        if (isLoggedIn(auth)) {
            CustomUserDetails user = (CustomUserDetails) auth.getPrincipal();
            model.addAttribute("loggedUser", user);
            contactRepository.findFirstByEmail(user.getUsername())
                    .ifPresent(c -> model.addAttribute("contact", c));
        }
        return "public/demande";
    }

    @PostMapping
    @Transactional
    public String submit(@RequestParam String subject,
                         @RequestParam String message,
                         @RequestParam(required = false) String firstName,
                         @RequestParam(required = false) String lastName,
                         @RequestParam(required = false) String email,
                         @RequestParam(required = false) String company,
                         Authentication auth,
                         RedirectAttributes ra) {

        Contact  contact       = null;
        Customer customer      = null;
        boolean  isNewProspect = false;

        if (isLoggedIn(auth)) {
            CustomUserDetails user = (CustomUserDetails) auth.getPrincipal();
            email   = user.getUsername();
            contact = contactRepository.findFirstByEmail(email).orElse(null);
            if (contact != null) {
                customer = contact.getCustomer();
            } else if (user.getCustomerId() != null) {
                customer = customerRepository.findById(user.getCustomerId()).orElse(null);
            }
        } else if (email != null && !email.isBlank()) {
            contact = contactRepository.findFirstByEmail(email.trim()).orElse(null);
            if (contact != null) {
                customer = contact.getCustomer();
            } else {
                // Créer un prospect + contact
                String name = (firstName != null ? firstName.trim() : "") + " " + (lastName != null ? lastName.trim() : "");
                customer = new Customer();
                customer.setCompanyName(company != null && !company.isBlank() ? company.trim() : name.trim());
                customer.setEmail(email.trim());
                customer.setProspect(true);
                customer.setCustomer(false);
                customer.setProspectStatus(ProspectStatus.NOUVEAU);
                customerRepository.save(customer);

                contact = new Contact();
                contact.setFirstName(firstName != null ? firstName.trim() : "");
                contact.setLastName(lastName != null ? lastName.trim() : "");
                contact.setEmail(email.trim());
                contact.setCustomer(customer);
                contactRepository.save(contact);
                isNewProspect = true;
            }
        }

        Request request = new Request();
        request.setTitle(subject);
        request.setDescription(message);
        request.setContact(contact);
        request.setCustomer(customer);
        request.setSenderEmail(email);
        request.setStatus(RequestStatus.NEW);
        request.registerChange();
        requestRepository.save(request);

        ra.addFlashAttribute("ticketId",      request.getId());
        ra.addFlashAttribute("isNewProspect", isNewProspect);
        ra.addFlashAttribute("visitorEmail",  email);
        return "redirect:/public/demande/confirmation";
    }

    @GetMapping("/confirmation")
    public String confirmation() {
        return "public/demande-confirm";
    }

    private boolean isLoggedIn(Authentication auth) {
        return auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
    }
}
