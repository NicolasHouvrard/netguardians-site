package com.logicielpourtous.site.features.registration;

import com.logicielpourtous.site.features.customers.*;
import com.logicielpourtous.site.features.portal.PortalUserService;
import com.logicielpourtous.site.features.user.UserRepository;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.UUID;

@Controller
@RequestMapping("/intra/registrations")
public class SubscriptionRequestController {

    private final SubscriptionRequestRepository repository;
    private final CustomerRepository            customerRepository;
    private final SiteRepository                siteRepository;
    private final ContactRepository             contactRepository;
    private final UserRepository                userRepository;
    private final PortalUserService             portalUserService;

    public SubscriptionRequestController(SubscriptionRequestRepository repository,
                                         CustomerRepository customerRepository,
                                         SiteRepository siteRepository,
                                         ContactRepository contactRepository,
                                         UserRepository userRepository,
                                         PortalUserService portalUserService) {
        this.repository         = repository;
        this.customerRepository = customerRepository;
        this.siteRepository     = siteRepository;
        this.contactRepository  = contactRepository;
        this.userRepository     = userRepository;
        this.portalUserService  = portalUserService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("requests", repository.findAllByOrderByRequestedAtDesc());
        model.addAttribute("pending",  repository.countByStatus(SubscriptionRequest.SubscriptionRequestStatus.PENDING));
        return "intra/registrations/list";
    }

    @PostMapping("/{id}/approve")
    @Transactional
    public String approve(@PathVariable Long id,
                          @RequestParam(required = false) String adminNotes,
                          RedirectAttributes ra) {

        SubscriptionRequest req = repository.findById(id).orElse(null);
        if (req == null || req.getStatus() != SubscriptionRequest.SubscriptionRequestStatus.PENDING) {
            ra.addFlashAttribute("errorMessage", "Demande introuvable ou déjà traitée.");
            return "redirect:/intra/registrations";
        }

        // ── 1. Trouver ou créer le Customer ──────────────────────────────────
        boolean isNewCustomer = false;
        Customer customer = customerRepository.findByEmail(req.getEmail()).orElse(null);

        if (customer == null) {
            isNewCustomer = true;
            customer = new Customer();
            customer.setCompanyName(req.getCompanyName());
            customer.setEmail(req.getEmail());
            customer.setContactName(req.getContactName());
            customer.setPhone(req.getPhone());
            customerRepository.save(customer);
        }

        // ── 2. Si nouveau client : créer le site + contact ───────────────────
        if (isNewCustomer) {
            Site siege = new Site();
            siege.setName("Siège");
            siege.setCustomer(customer);
            siteRepository.save(siege);

            Contact contact = new Contact();
            String[] parts = req.getContactName().trim().split(" ", 2);
            contact.setFirstName(parts[0]);
            contact.setLastName(parts.length > 1 ? parts[1] : "");
            contact.setEmail(req.getEmail());
            contact.setPhone(req.getPhone());
            contact.setCustomer(customer);
            contact.setSite(siege);
            contactRepository.save(contact);

            // Créer le compte portail si aucun compte n'existe pour cet email
            if (userRepository.findByEmail(req.getEmail()).isEmpty()) {
                String tempPwd = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
                PortalUserService.CreationResult result =
                        portalUserService.createExplicit(req.getEmail(), req.getContactName(),
                                tempPwd, customer.getId(), "GESTIONNAIRE");
                contact.setUserId(result.user().getId());
                contactRepository.save(contact);
                ra.addFlashAttribute("createdEmail",    req.getEmail());
                ra.addFlashAttribute("createdPassword", tempPwd);
            }
        } else {
            // Client existant — créer le compte s'il n'en a pas encore
            if (userRepository.findByEmail(req.getEmail()).isEmpty()) {
                String tempPwd = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
                portalUserService.createExplicit(req.getEmail(), req.getContactName(),
                        tempPwd, customer.getId(), "GESTIONNAIRE");
                ra.addFlashAttribute("createdEmail",    req.getEmail());
                ra.addFlashAttribute("createdPassword", tempPwd);
            }
            ra.addFlashAttribute("linkedCompany", customer.getCompanyName());
        }

        // ── 3. Valider la demande ─────────────────────────────────────────────
        req.setStatus(SubscriptionRequest.SubscriptionRequestStatus.APPROVED);
        req.setProcessedAt(LocalDateTime.now());
        if (adminNotes != null && !adminNotes.isBlank()) req.setAdminNotes(adminNotes);
        repository.save(req);

        ra.addFlashAttribute("successMessage",
                "Demande approuvée — compte créé pour " + req.getCompanyName() + ".");
        return "redirect:/intra/registrations";
    }

    @PostMapping("/{id}/reject")
    public String reject(@PathVariable Long id,
                         @RequestParam(required = false) String adminNotes,
                         RedirectAttributes ra) {
        repository.findById(id).ifPresent(r -> {
            r.setStatus(SubscriptionRequest.SubscriptionRequestStatus.REJECTED);
            r.setProcessedAt(LocalDateTime.now());
            if (adminNotes != null && !adminNotes.isBlank()) r.setAdminNotes(adminNotes);
            repository.save(r);
        });
        ra.addFlashAttribute("successMessage", "Demande refusée.");
        return "redirect:/intra/registrations";
    }
}
