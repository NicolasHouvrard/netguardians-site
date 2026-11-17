package com.logicielpourtous.site.features.plateforme;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Négation exacte de {@link SurServeurMaitre} : serveur PARTNER, CLIENT ou PUBLIC, qui tire ses plugins du Maître. */
class HorsServeurMaitre implements Condition {

	@Override
	public boolean matches(ConditionContext contexte, AnnotatedTypeMetadata metadonnees) {
		return !new SurServeurMaitre().matches(contexte, metadonnees);
	}

}
