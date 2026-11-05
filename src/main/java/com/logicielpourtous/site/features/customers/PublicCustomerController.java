package com.logicielpourtous.site.features.customers;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.logicielpourtous.site.features.ninja.NinjaOneClient;
import com.logicielpourtous.site.features.ninja.NinjaOneConfig;
import com.logicielpourtous.site.features.ninja.NinjaOneConfigRepository;
import com.logicielpourtous.site.features.ninja.NinjaOrganization;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.role.RoleRepository;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import jakarta.validation.Valid;

@Controller
@RequestMapping("/intra/customers")
public class PublicCustomerController {

    private final CustomerRepository       customerRepository;
    private final SiteRepository           siteRepository;
    private final NinjaOneConfigRepository ninjaConfigRepository;
    private final NinjaOneClient           ninjaOneClient;
    private final UserRepository           userRepository;
    private final RoleRepository           roleRepository;
    private final TenantRepository         tenantRepository;
    private final PasswordEncoder          passwordEncoder;

    public PublicCustomerController(CustomerRepository customerRepository,
                                    SiteRepository siteRepository,
                                    NinjaOneConfigRepository ninjaConfigRepository,
                                    NinjaOneClient ninjaOneClient,
                                    UserRepository userRepository,
                                    RoleRepository roleRepository,
                                    TenantRepository tenantRepository,
                                    PasswordEncoder passwordEncoder) {
        this.customerRepository   = customerRepository;
        this.siteRepository       = siteRepository;
        this.ninjaConfigRepository = ninjaConfigRepository;
        this.ninjaOneClient        = ninjaOneClient;
        this.userRepository        = userRepository;
        this.roleRepository        = roleRepository;
        this.tenantRepository      = tenantRepository;
        this.passwordEncoder       = passwordEncoder;
    }

    @GetMapping("/add")
    public String addForm(Model model) {
        model.addAttribute("customer", new Customer());
        addNinjaOrgs(model);
        return "feature/customers/form";
    }

    @PostMapping("/save")
    public String save(@Valid @ModelAttribute("customer") Customer customer,
                       BindingResult result,
                       RedirectAttributes redirectAttributes) {

        if (result.hasErrors()) {
            return "feature/customers/form";
        }

        boolean isNew = customer.getId() == null;

        // Évite que CascadeType.ALL tente de re-persister les enfants envoyés par erreur
        if (!isNew) {
            customer.setContacts(new ArrayList<>());
            customer.setSites(new ArrayList<>());
            customer.setRequests(new ArrayList<>());
        }

        try {
            customerRepository.save(customer);

            // Création automatique du site "Siège" pour tout nouveau client
            if (isNew) {
                Site siege = new Site();
                siege.setName("Siège");
                siege.setCustomer(customer);
                siteRepository.save(siege);
                redirectAttributes.addFlashAttribute("successMessage",
                        "Client créé avec un site « Siège » par défaut.");
                return "redirect:/intra/customers/" + customer.getId();
            }

            redirectAttributes.addFlashAttribute("successMessage", "Fiche client mise à jour.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Erreur : " + e.getMessage());
        }

        return "redirect:/intra/customers";
    }

    @GetMapping("/edit/{id}")
    public String editForm(@PathVariable Long id) {
        return "redirect:/intra/customers/" + id;
    }

    @PostMapping("/{id}/portal-user")
    public String createPortalUser(@PathVariable Long id,
                                   @RequestParam String email,
                                   @RequestParam(required = false) String fullName,
                                   @RequestParam(required = false) String password,
                                   @RequestParam(defaultValue = "CLIENT") String role,
                                   RedirectAttributes redirectAttributes) {
        String roleName = "GESTIONNAIRE".equals(role) ? "GESTIONNAIRE" : "CLIENT";
        Role assignedRole = roleRepository.findByName(roleName)
                .orElseGet(() -> roleRepository.save(new Role(roleName)));

        java.util.Optional<User> existing = userRepository.findByEmail(email);
        if (existing.isPresent()) {
            User u = existing.get();
            if (u.getCustomerId() != null && !u.getCustomerId().equals(id)) {
                redirectAttributes.addFlashAttribute("errorMessage",
                        "Ce compte est déjà lié à un autre client.");
                return "redirect:/intra/customers/" + id;
            }
            u.setCustomerId(id);
            u.getRoles().add(assignedRole);
            userRepository.save(u);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Compte existant " + email + " lié au portail (" + roleName + ").");
            return "redirect:/intra/customers/" + id;
        }

        if (password == null || password.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Le mot de passe est requis pour un nouveau compte.");
            return "redirect:/intra/customers/" + id;
        }

        Tenant tenant = tenantRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Aucun tenant trouvé"));

        User user = new User();
        user.setEmail(email);
        user.setFullName(fullName != null ? fullName : email);
        user.setPassword(passwordEncoder.encode(password));
        user.setCustomerId(id);
        user.setTenant(tenant);
        user.getRoles().add(assignedRole);

        userRepository.save(user);
        redirectAttributes.addFlashAttribute("successMessage", "Accès portail créé pour " + email + " (" + roleName + ")");
        return "redirect:/intra/customers/" + id;
    }

    @GetMapping("/{customerId}/portal-user/{userId}/delete")
    public String deletePortalUser(@PathVariable Long customerId,
                                   @PathVariable Long userId,
                                   RedirectAttributes redirectAttributes) {
        userRepository.findById(userId).ifPresent(u -> {
            if (customerId.equals(u.getCustomerId())) userRepository.delete(u);
        });
        redirectAttributes.addFlashAttribute("successMessage", "Accès portail supprimé.");
        return "redirect:/intra/customers/" + customerId;
    }

    @GetMapping("/delete/{id}")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        customerRepository.deleteById(id);
        redirectAttributes.addFlashAttribute("message", "Client supprimé.");
        return "redirect:/intra/customers";
    }

    private void addNinjaOrgs(Model model) {
        try {
            NinjaOneConfig cfg = ninjaConfigRepository.findById(1L).orElse(null);
            if (cfg != null && cfg.isEnabled()) {
                List<NinjaOrganization> orgs = ninjaOneClient.getOrganizations(cfg);
                model.addAttribute("ninjaOrgs", orgs);
            }
        } catch (Exception e) {
            // NinjaOne indisponible — on affiche le formulaire sans le select
        }
    }
}
