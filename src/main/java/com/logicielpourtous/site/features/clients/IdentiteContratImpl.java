package com.logicielpourtous.site.features.clients;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.ServerRoleProperties;
import com.logicielpourtous.site.features.user.Appartenance;
import com.logicielpourtous.site.sdk.IdentiteContrat;

/**
 * Identité de la requête en cours, lue dans le contexte de sécurité. La société est celle de l'appartenance choisie
 * pour la session (l'appartenance active du jeton ou de la session, ou l'unique appartenance de la personne) ;
 * jamais devinée : une appartenance sans société du registre (historique) donne {@code null}.
 */
@Component
class IdentiteContratImpl implements IdentiteContrat {

	private final ServerRoleProperties role;

	IdentiteContratImpl(ServerRoleProperties role) {
		this.role = role;
	}

	@Override
	public IdentiteCourante courante() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken
				|| !(auth.getPrincipal() instanceof CustomUserDetails compte)) {
			throw new IllegalStateException("Aucun utilisateur authentifié : l'identité courante est indisponible.");
		}
		if (compte.selectionRequise()) {
			throw new IllegalStateException(
					"Aucune société n'a encore été choisie pour cette session : l'identité courante est indisponible.");
		}
		Set<String> roles = compte.getAuthorities()
			.stream()
			.map(GrantedAuthority::getAuthority)
			.collect(Collectors.toSet());
		Long tenantId = compte.getTenant() == null ? null : compte.getTenant().getId();
		return new IdentiteCourante(compte.getUserId(), roles, societeChoisie(compte, tenantId), tenantId,
				role.getRole().name());
	}

	private static Long societeChoisie(CustomUserDetails compte, Long tenantId) {
		List<Appartenance> toutes = compte.appartenances();
		Appartenance choisie = null;
		Long actif = compte.appartenanceActiveId();
		if (actif != null) {
			choisie = toutes.stream().filter(a -> actif.equals(a.getId())).findFirst().orElse(null);
		}
		if (choisie == null && toutes.size() == 1) {
			choisie = toutes.get(0);
		}
		if (choisie == null || choisie.getTenant() == null || !Objects.equals(choisie.getTenant().getId(), tenantId)) {
			return null; // incohérence avec le tenant effectif : on ne devine pas
		}
		return choisie.getSocieteId();
	}

}
