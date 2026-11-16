package com.logicielpourtous.site.features.plateforme;

import com.logicielpourtous.site.features.commun.ServerRole;
import com.logicielpourtous.site.features.commun.ServerRoleProperties;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Condition « ce serveur est le Maître » : {@code app.role} interprété exactement comme {@link ServerRoleProperties}
 * (casse indifférente ; absent ou invalide = {@code MASTER}, repli historique du cœur), pour que le dépôt n'existe que
 * là où le reste du cœur se considère Maître. À combiner avec {@code app.plateforme.actif=true}.
 */
class SurServeurMaitre implements Condition {

	@Override
	public boolean matches(ConditionContext contexte, AnnotatedTypeMetadata metadonnees) {
		ServerRoleProperties role = new ServerRoleProperties();
		role.setRole(contexte.getEnvironment().getProperty("app.role"));
		return role.getRole() == ServerRole.MASTER;
	}

}
