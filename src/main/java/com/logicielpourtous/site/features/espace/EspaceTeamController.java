package com.logicielpourtous.site.features.espace;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.customers.Contact;
import com.logicielpourtous.site.features.customers.ContactRepository;
import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.portal.PortalUserService;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;

@Controller
@RequestMapping("/espace/team")
public class EspaceTeamController {

    private final UserRepository       userRepository;
    private final ContactRepository    contactRepository;
    private final PortalUserService    portalUserService;
    private final EspaceContextService ctx;

    public EspaceTeamController(UserRepository userRepository,
                                ContactRepository contactRepository,
                                PortalUserService portalUserService,
                                EspaceContextService ctx) {
        this.userRepository    = userRepository;
        this.contactRepository = contactRepository;
        this.portalUserService = portalUserService;
        this.ctx               = ctx;
    }

    @GetMapping
    public String list(@AuthenticationPrincipal CustomUserDetails userDetails,
                       HttpSession session, Model model) {
        Customer customer = ctx.loadCustomer(userDetails, session);
        List<Contact> contacts = contactRepository.findByCustomerId(customer.getId());

        Map<Long, User> userMap = new HashMap<>();
        Set<Long> gestionnaireUserIds = new HashSet<>();
        contacts.stream()
                .filter(c -> c.getUserId() != null)
                .forEach(c -> userRepository.findById(c.getUserId()).ifPresent(u -> {
                    userMap.put(c.getUserId(), u);
                    if (u.getRoles().stream().anyMatch(r -> r.getName().equals("GESTIONNAIRE"))) {
                        gestionnaireUserIds.add(u.getId());
                    }
                }));

        model.addAttribute("customer",            customer);
        model.addAttribute("contacts",            contacts);
        model.addAttribute("userMap",             userMap);
        model.addAttribute("gestionnaireUserIds", gestionnaireUserIds);
        model.addAttribute("currentUserEmail",    userDetails.getUsername());
        return "espace/team";
    }

    @PostMapping("/add")
    public String add(@AuthenticationPrincipal CustomUserDetails userDetails,
                      HttpSession session,
                      @RequestParam String firstName,
                      @RequestParam String lastName,
                      @RequestParam(required = false) String email,
                      @RequestParam(required = false) String phone,
                      @RequestParam(required = false) String position,
                      @RequestParam(required = false) String password,
                      @RequestParam(defaultValue = "CLIENT") String role,
                      RedirectAttributes ra) {
        Customer customer = ctx.loadCustomer(userDetails, session);

        Contact contact = new Contact();
        contact.setFirstName(firstName);
        contact.setLastName(lastName);
        contact.setEmail(email);
        contact.setPhone(phone);
        contact.setPosition(position);
        contact.setCustomer(customer);

        if (email != null && !email.isBlank()) {
            if (userRepository.findByEmail(email).isPresent()) {
                ra.addFlashAttribute("warningMessage",
                        firstName + " " + lastName + " ajouté (sans accès portail : cet email est déjà utilisé).");
            } else {
                String pwd = (password != null && !password.isBlank())
                        ? password
                        : UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                PortalUserService.CreationResult result =
                        portalUserService.createExplicit(email, firstName + " " + lastName, pwd, customer.getId(), role);
                contact.setUserId(result.user().getId());
                ra.addFlashAttribute("successMessage",
                        firstName + " " + lastName + " ajouté — accès portail créé. Mot de passe : " + pwd);
            }
        } else {
            ra.addFlashAttribute("successMessage", firstName + " " + lastName + " ajouté.");
        }

        contactRepository.save(contact);
        return "redirect:/espace/team";
    }

    @PostMapping("/contacts/{cid}/update")
    public String update(@PathVariable Long cid,
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
                .filter(c -> c.getCustomer().getId().equals(customer.getId()))
                .orElseThrow(() -> new IllegalArgumentException("Contact introuvable"));

        contact.setFirstName(firstName);
        contact.setLastName(lastName);
        contact.setEmail(email);
        contact.setPhone(phone);
        contact.setPosition(position);

        if (contact.getUserId() != null) {
            userRepository.findById(contact.getUserId()).ifPresent(u -> {
                u.setFullName(firstName + " " + lastName);
                if (email != null && !email.isBlank()) u.setEmail(email);
                userRepository.save(u);
            });
        }

        contactRepository.save(contact);
        ra.addFlashAttribute("successMessage", firstName + " " + lastName + " mis à jour.");
        return "redirect:/espace/team";
    }

    @PostMapping("/contacts/{cid}/create-access")
    public String createAccess(@PathVariable Long cid,
                               @AuthenticationPrincipal CustomUserDetails userDetails,
                               HttpSession session,
                               @RequestParam(defaultValue = "CLIENT") String role,
                               RedirectAttributes ra) {
        Customer customer = ctx.loadCustomer(userDetails, session);
        Contact contact = contactRepository.findById(cid)
                .filter(c -> c.getCustomer().getId().equals(customer.getId()))
                .orElseThrow(() -> new IllegalArgumentException("Contact introuvable"));

        if (contact.getEmail() == null || contact.getEmail().isBlank()) {
            ra.addFlashAttribute("errorMessage", "Ce contact n'a pas d'adresse email — impossible de créer un accès portail.");
            return "redirect:/espace/team";
        }
        if (userRepository.findByEmail(contact.getEmail()).isPresent()) {
            ra.addFlashAttribute("errorMessage", "Impossible de créer l'accès : cet email est déjà associé à un compte existant.");
            return "redirect:/espace/team";
        }

        String pwd = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        PortalUserService.CreationResult result =
                portalUserService.createExplicit(contact.getEmail(), contact.getFullName(), pwd, customer.getId(), role);
        contact.setUserId(result.user().getId());
        contactRepository.save(contact);
        ra.addFlashAttribute("successMessage",
                "Accès portail créé pour " + contact.getFullName() + " — mot de passe : " + pwd);
        return "redirect:/espace/team";
    }

    @PostMapping("/contacts/{cid}/revoke-access")
    public String revokeAccess(@PathVariable Long cid,
                               @AuthenticationPrincipal CustomUserDetails userDetails,
                               HttpSession session,
                               RedirectAttributes ra) {
        Customer customer = ctx.loadCustomer(userDetails, session);
        Contact contact = contactRepository.findById(cid)
                .filter(c -> c.getCustomer().getId().equals(customer.getId()))
                .orElseThrow(() -> new IllegalArgumentException("Contact introuvable"));

        if (contact.getUserId() != null) {
            userRepository.findById(contact.getUserId()).ifPresent(u -> {
                if (u.getEmail().equals(userDetails.getUsername())) {
                    throw new IllegalStateException("Vous ne pouvez pas supprimer votre propre accès.");
                }
                userRepository.delete(u);
            });
            contact.setUserId(null);
            contactRepository.save(contact);
        }
        ra.addFlashAttribute("successMessage", "Accès portail révoqué pour " + contact.getFullName() + ".");
        return "redirect:/espace/team";
    }

    @PostMapping("/contacts/{cid}/delete")
    public String delete(@PathVariable Long cid,
                         @AuthenticationPrincipal CustomUserDetails userDetails,
                         HttpSession session,
                         RedirectAttributes ra) {
        Customer customer = ctx.loadCustomer(userDetails, session);
        Contact contact = contactRepository.findById(cid)
                .filter(c -> c.getCustomer().getId().equals(customer.getId()))
                .orElseThrow(() -> new IllegalArgumentException("Contact introuvable"));

        if (contact.getUserId() != null) {
            userRepository.findById(contact.getUserId()).ifPresent(u -> {
                if (u.getEmail().equals(userDetails.getUsername())) {
                    throw new IllegalStateException("Vous ne pouvez pas supprimer votre propre compte.");
                }
                userRepository.delete(u);
            });
        }
        contactRepository.delete(contact);
        ra.addFlashAttribute("successMessage", contact.getFullName() + " supprimé.");
        return "redirect:/espace/team";
    }

    @PostMapping("/contacts/{cid}/promote")
    public String promote(@PathVariable Long cid,
                          @AuthenticationPrincipal CustomUserDetails userDetails,
                          HttpSession session,
                          RedirectAttributes ra) {
        Customer customer = ctx.loadCustomer(userDetails, session);
        Contact contact = contactRepository.findById(cid)
                .filter(c -> c.getCustomer().getId().equals(customer.getId()))
                .orElseThrow(() -> new IllegalArgumentException("Contact introuvable"));

        if (contact.getUserId() == null) {
            ra.addFlashAttribute("errorMessage", "Ce contact n'a pas d'accès portail.");
            return "redirect:/espace/team";
        }
        userRepository.findById(contact.getUserId()).ifPresent(portalUserService::promoteToGestionnaire);
        ra.addFlashAttribute("successMessage", contact.getFullName() + " promu Gestionnaire.");
        return "redirect:/espace/team";
    }

    @PostMapping("/contacts/{cid}/demote")
    public String demote(@PathVariable Long cid,
                         @AuthenticationPrincipal CustomUserDetails userDetails,
                         HttpSession session,
                         RedirectAttributes ra) {
        Customer customer = ctx.loadCustomer(userDetails, session);
        Contact contact = contactRepository.findById(cid)
                .filter(c -> c.getCustomer().getId().equals(customer.getId()))
                .orElseThrow(() -> new IllegalArgumentException("Contact introuvable"));

        if (contact.getUserId() == null) {
            ra.addFlashAttribute("errorMessage", "Ce contact n'a pas d'accès portail.");
            return "redirect:/espace/team";
        }
        userRepository.findById(contact.getUserId()).ifPresent(portalUserService::demoteToClient);
        ra.addFlashAttribute("successMessage", contact.getFullName() + " rétrogradé Utilisateur.");
        return "redirect:/espace/team";
    }
}
