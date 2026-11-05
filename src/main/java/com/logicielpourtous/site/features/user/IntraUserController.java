package com.logicielpourtous.site.features.user;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.customers.Contact;
import com.logicielpourtous.site.features.customers.ContactRepository;
import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.role.RoleRepository;
import com.logicielpourtous.site.features.team.Team;
import com.logicielpourtous.site.features.team.TeamRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/intra/users")
public class IntraUserController {

    private final UserRepository     userRepository;
    private final RoleRepository     roleRepository;
    private final CustomerRepository customerRepository;
    private final ContactRepository  contactRepository;
    private final TeamRepository     teamRepository;
    private final TenantRepository   tenantRepository;
    private final PasswordEncoder    passwordEncoder;

    public IntraUserController(UserRepository userRepository,
                               RoleRepository roleRepository,
                               CustomerRepository customerRepository,
                               ContactRepository contactRepository,
                               TeamRepository teamRepository,
                               TenantRepository tenantRepository,
                               PasswordEncoder passwordEncoder) {
        this.userRepository     = userRepository;
        this.roleRepository     = roleRepository;
        this.customerRepository = customerRepository;
        this.contactRepository  = contactRepository;
        this.teamRepository     = teamRepository;
        this.tenantRepository   = tenantRepository;
        this.passwordEncoder    = passwordEncoder;
    }

    // ── Liste ────────────────────────────────────────────────────────────────

    @GetMapping
    public String list(Model model, @AuthenticationPrincipal CustomUserDetails current) {
        boolean isAdmin = hasRole(current, "ROLE_ADMIN");
        List<User> users = userRepository.findAll();

        List<Role> esnRoles = isAdmin
                ? roleRepository.findByTypeOrderByLabel("ESN")
                : roleRepository.findByTypeOrderByLabel("ESN").stream()
                        .filter(r -> !r.getName().equals("ROLE_ADMIN")).toList();
        List<Role> clientRoles = roleRepository.findByTypeOrderByLabel("CLIENT");

        // Customer name pour les utilisateurs portail (customerId non null)
        Set<Long> customerIds = users.stream()
                .filter(u -> u.getCustomerId() != null)
                .map(User::getCustomerId)
                .collect(Collectors.toSet());
        Map<Long, String> customerNames = customerRepository.findAllById(customerIds).stream()
                .collect(Collectors.toMap(Customer::getId, Customer::getCompanyName));

        // Contact lié à chaque user (pour les utilisateurs internes ESN)
        List<Long> userIds = users.stream().map(User::getId).toList();
        Map<Long, Contact> contactByUserId = contactRepository.findByUserIdIn(userIds).stream()
                .collect(Collectors.toMap(Contact::getUserId, c -> c));

        // Équipes par userId
        List<Team> teams = teamRepository.findAllWithMembers();
        Map<Long, List<String>> userTeams = new HashMap<>();
        for (Team t : teams) {
            for (User m : t.getMembers()) {
                userTeams.computeIfAbsent(m.getId(), k -> new ArrayList<>()).add(t.getName());
            }
        }

        // Tous les customers pour le formulaire de liaison
        List<Customer> allCustomers = customerRepository.findAll()
                .stream().sorted(Comparator.comparing(Customer::getCompanyName)).toList();

        model.addAttribute("users",            users);
        model.addAttribute("customerNames",    customerNames);
        model.addAttribute("contactByUserId",  contactByUserId);
        model.addAttribute("allCustomers",     allCustomers);
        model.addAttribute("userTeams",        userTeams);
        model.addAttribute("esnRoles",         esnRoles);
        model.addAttribute("clientRoles",      clientRoles);
        model.addAttribute("isAdmin",          isAdmin);
        return "intra/users/list";
    }

    // ── Création ─────────────────────────────────────────────────────────────

    @PostMapping
    public String create(@RequestParam String fullName,
                         @RequestParam String email,
                         @RequestParam String password,
                         @RequestParam(required = false) List<Long> roleIds,
                         @RequestParam(required = false) Long customerId,
                         @AuthenticationPrincipal CustomUserDetails current,
                         RedirectAttributes ra) {
        if (userRepository.findByEmail(email).isPresent()) {
            ra.addFlashAttribute("errorMessage", "Un utilisateur avec cet email existe déjà.");
            return "redirect:/intra/users";
        }

        Tenant tenant = current != null ? current.getTenant()
                : tenantRepository.findAll().stream().findFirst().orElse(null);

        User user = new User();
        user.setFullName(fullName);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(password));
        user.setActive(true);
        user.setTenant(tenant);
        user.setRoles(resolveRoles(roleIds, current));
        userRepository.save(user);

        // Création du contact associé si un client est sélectionné
        if (customerId != null) {
            customerRepository.findById(customerId).ifPresent(customer -> {
                createContactForUser(user, fullName, email, customer);
            });
        }

        ra.addFlashAttribute("successMessage", "Utilisateur « " + fullName + " » créé.");
        return "redirect:/intra/users";
    }

    // ── Liaison utilisateur ↔ contact client ──────────────────────────────────

    @PostMapping("/{id}/link-customer")
    public String linkCustomer(@PathVariable Long id,
                               @RequestParam Long customerId,
                               RedirectAttributes ra) {
        User user = userRepository.findById(id).orElse(null);
        if (user == null) return "redirect:/intra/users";

        Customer customer = customerRepository.findById(customerId).orElse(null);
        if (customer == null) return "redirect:/intra/users";

        // Met à jour le contact existant ou en crée un nouveau
        Contact contact = contactRepository.findByUserId(id).orElseGet(() -> {
            Contact c = new Contact();
            c.setUserId(id);
            return c;
        });
        String[] parts = (user.getFullName() != null ? user.getFullName().trim() : "").split(" ", 2);
        contact.setFirstName(parts[0]);
        contact.setLastName(parts.length > 1 ? parts[1] : "");
        contact.setEmail(user.getEmail());
        contact.setCustomer(customer);
        contactRepository.save(contact);

        ra.addFlashAttribute("successMessage",
                user.getFullName() + " est maintenant contact de « " + customer.getCompanyName() + " ».");
        return "redirect:/intra/users";
    }

    @PostMapping("/{id}/unlink-customer")
    public String unlinkCustomer(@PathVariable Long id, RedirectAttributes ra) {
        contactRepository.findByUserId(id).ifPresent(c -> {
            c.setUserId(null);
            contactRepository.save(c);
        });
        ra.addFlashAttribute("successMessage", "Liaison contact supprimée.");
        return "redirect:/intra/users";
    }

    // ── Mise à jour des rôles ─────────────────────────────────────────────────

    @PostMapping("/{id}/roles")
    public String updateRoles(@PathVariable Long id,
                              @RequestParam(required = false) List<Long> roleIds,
                              @AuthenticationPrincipal CustomUserDetails current,
                              RedirectAttributes ra) {
        userRepository.findById(id).ifPresent(user -> {
            user.setRoles(resolveRoles(roleIds, current));
            userRepository.save(user);
        });
        ra.addFlashAttribute("successMessage", "Rôles mis à jour.");
        return "redirect:/intra/users";
    }

    // ── Activation / désactivation ────────────────────────────────────────────

    @PostMapping("/{id}/toggle-active")
    public String toggleActive(@PathVariable Long id, RedirectAttributes ra) {
        userRepository.findById(id).ifPresent(user -> {
            user.setActive(!user.isActive());
            userRepository.save(user);
            ra.addFlashAttribute("successMessage",
                    user.isActive() ? "Compte activé." : "Compte désactivé.");
        });
        return "redirect:/intra/users";
    }

    // ── Réinitialisation mot de passe ─────────────────────────────────────────

    @PostMapping("/{id}/reset-password")
    public String resetPassword(@PathVariable Long id, RedirectAttributes ra) {
        String temp = "Reset" + id + "!" + (int)(Math.random() * 9000 + 1000);
        userRepository.findById(id).ifPresent(user -> {
            user.setPassword(passwordEncoder.encode(temp));
            userRepository.save(user);
        });
        ra.addFlashAttribute("successMessage", "Mot de passe temporaire : " + temp);
        return "redirect:/intra/users";
    }

    // ── Suppression ───────────────────────────────────────────────────────────

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         @AuthenticationPrincipal CustomUserDetails current,
                         RedirectAttributes ra) {
        if (!hasRole(current, "ROLE_ADMIN")) {
            ra.addFlashAttribute("errorMessage", "Seul un administrateur peut supprimer un utilisateur.");
            return "redirect:/intra/users";
        }
        userRepository.findById(id).ifPresent(user -> {
            // Délier le contact avant suppression
            contactRepository.findByUserId(id).ifPresent(c -> {
                c.setUserId(null);
                contactRepository.save(c);
            });
            userRepository.delete(user);
            ra.addFlashAttribute("successMessage", "Utilisateur « " + user.getFullName() + " » supprimé.");
        });
        return "redirect:/intra/users";
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void createContactForUser(User user, String fullName, String email, Customer customer) {
        Contact contact = contactRepository.findByUserId(user.getId()).orElse(new Contact());
        String[] parts = (fullName != null ? fullName.trim() : "").split(" ", 2);
        contact.setFirstName(parts[0]);
        contact.setLastName(parts.length > 1 ? parts[1] : "");
        contact.setEmail(email);
        contact.setCustomer(customer);
        contact.setUserId(user.getId());
        contactRepository.save(contact);
    }

    private boolean hasRole(CustomUserDetails user, String role) {
        return user != null && user.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(role));
    }

    private Set<Role> resolveRoles(List<Long> roleIds, CustomUserDetails current) {
        if (roleIds == null || roleIds.isEmpty()) return new HashSet<>();
        boolean isAdmin = hasRole(current, "ROLE_ADMIN");
        Set<Role> roles = new HashSet<>();
        for (Long id : roleIds) {
            roleRepository.findById(id).ifPresent(r -> {
                if (!r.getName().equals("ROLE_ADMIN") || isAdmin) roles.add(r);
            });
        }
        return roles;
    }
}
