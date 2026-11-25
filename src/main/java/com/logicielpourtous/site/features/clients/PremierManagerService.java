package com.logicielpourtous.site.features.clients;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.role.RoleRepository;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;

/** Creation du compte du premier manager d'une societe (client ou partenaire), dans la transaction de l'appelant. */
@Service
class PremierManagerService {

	private final UserRepository users;

	private final RoleRepository roles;

	private final TenantRepository tenants;

	private final PasswordEncoder passwordEncoder;

	PremierManagerService(UserRepository users, RoleRepository roles, TenantRepository tenants,
			PasswordEncoder passwordEncoder) {
		this.users = users;
		this.roles = roles;
		this.tenants = tenants;
		this.passwordEncoder = passwordEncoder;
	}

	/** Compte actif existant pour cette adresse (casse ignorée) ; vide si aucun compte ou si le compte est inactif. */
	Optional<User> trouverActif(String emailNormalise) {
		return users.findByEmailIgnoreCase(emailNormalise).filter(User::isActive);
	}

	/** Compte du premier manager : mot de passe aléatoire inutilisable, à définir via « mot de passe oublié ». */
	User creer(String email, String nom, Long tenantId) {
		Tenant tenant = tenants.findById(tenantId)
			.orElseThrow(() -> new IllegalStateException("La société partenaire n'a pas de tenant propriétaire valide."));
		User user = new User();
		user.setEmail(email);
		user.setFullName(nom);
		user.setPassword(passwordEncoder.encode(UUID.randomUUID() + UUID.randomUUID().toString()));
		user.setTenant(tenant);
		user.setMustChangePassword(true);
		user.setActive(true);
		roles.findByName(ManagersService.ROLE_MANAGER).ifPresent(r -> user.getRoles().add(r));
		return users.saveAndFlush(user);
	}

}
