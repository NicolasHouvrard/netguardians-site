package com.logicielpourtous.site.features.plateforme;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Beans de la plateforme de plugins qui ne sont pas des composants : propriétés, registre de contrats et clés publiques
 * de confiance.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.plateforme.actif", havingValue = "true")
@EnableConfigurationProperties(PlateformeProperties.class)
public class PlateformeConfiguration {

	@Bean
	ContratRegistre contratRegistre() {
		return new ContratRegistre();
	}

	/**
	 * Clés de confiance lues dans la ressource du cœur, et nulle part ailleurs. Sans clé utilisable alors que la
	 * signature est obligatoire, la création échoue : la plateforme ne démarre pas plutôt que d'accepter tout jar.
	 */
	@Bean
	ClesPubliques clesPubliques(PlateformeProperties proprietes) {
		return ClesPubliques.depuisRessource(proprietes.isSignatureObligatoire());
	}

}
