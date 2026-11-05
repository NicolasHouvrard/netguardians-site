package com.logicielpourtous.site.features.portal;

import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.customers.Contact;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.role.RoleRepository;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
public class PortalUserService {

    private final UserRepository    userRepository;
    private final RoleRepository    roleRepository;
    private final TenantRepository  tenantRepository;
    private final PasswordEncoder   passwordEncoder;

    public PortalUserService(UserRepository userRepository,
                             RoleRepository roleRepository,
                             TenantRepository tenantRepository,
                             PasswordEncoder passwordEncoder) {
        this.userRepository   = userRepository;
        this.roleRepository   = roleRepository;
        this.tenantRepository = tenantRepository;
        this.passwordEncoder  = passwordEncoder;
    }

    public record CreationResult(User user, String tempPassword) {}

    /**
     * Crée un compte portail pour un contact s'il n'en possède pas déjà un.
     * Retourne un Optional vide si l'email est absent ou déjà enregistré.
     */
    public Optional<CreationResult> createForContact(Contact contact, Long customerId, String... roleNames) {
        if (contact.getEmail() == null || contact.getEmail().isBlank()) return Optional.empty();
        if (userRepository.findByEmail(contact.getEmail()).isPresent())  return Optional.empty();

        String tempPwd = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        User user = buildUser(contact.getEmail(), contact.getFullName(), customerId, tempPwd, roleNames);
        userRepository.save(user);
        return Optional.of(new CreationResult(user, tempPwd));
    }

    /** Crée un compte portail à partir d'un email / nom explicites (inscription prospect). */
    public CreationResult createExplicit(String email, String fullName, String rawPassword,
                                         Long customerId, String roleName) {
        User user = buildUser(email, fullName, customerId, rawPassword, roleName);
        userRepository.save(user);
        return new CreationResult(user, rawPassword);
    }

    public void promoteToGestionnaire(User user) {
        Role g = ensureRole("GESTIONNAIRE");
        Role c = roleRepository.findByName("CLIENT").orElse(null);
        if (c != null) user.getRoles().remove(c);
        user.getRoles().add(g);
        userRepository.save(user);
    }

    public void demoteToClient(User user) {
        Role c = ensureRole("CLIENT");
        Role g = roleRepository.findByName("GESTIONNAIRE").orElse(null);
        if (g != null) user.getRoles().remove(g);
        user.getRoles().add(c);
        userRepository.save(user);
    }

    // ── private helpers ──────────────────────────────────────────────────

    private User buildUser(String email, String fullName, Long customerId,
                            String rawPassword, String... roleNames) {
        Tenant tenant = tenantRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Aucun tenant trouvé"));
        User user = new User();
        user.setEmail(email);
        user.setFullName(fullName != null ? fullName : email);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setCustomerId(customerId);
        user.setTenant(tenant);
        user.setMustChangePassword(true);
        for (String roleName : roleNames) {
            user.getRoles().add(ensureRole(roleName));
        }
        return user;
    }

    private Role ensureRole(String name) {
        return roleRepository.findByName(name)
                .orElseGet(() -> roleRepository.save(new Role(name)));
    }
}
