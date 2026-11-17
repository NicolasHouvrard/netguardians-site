package com.logicielpourtous.site.features.plateforme;

import com.logicielpourtous.site.features.commun.CleMaitre;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
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

	/**
	 * Source du tireur hors du Maître : le Maître par HTTP. URL : {@code app.plateforme.master-url}, sinon celle de la
	 * licence ({@code app.licence.master-url}) ; clé : {@link CleMaitre} (module {@code licence}, lue à chaque appel ;
	 * absente = installation non enrôlée, le tirage ne fait rien). Sur le Maître, c'est {@link SourceDepotLocal}.
	 */
	@Bean
	@Conditional(HorsServeurMaitre.class)
	SourceMaitreHttp sourceMaitreHttp(PlateformeProperties proprietes, Environment environnement,
			ObjectProvider<CleMaitre> cle, ObjectProvider<SurveillanceMetaspace> surveillance) {
		String url = proprietes.getMasterUrl().isBlank() ? environnement.getProperty("app.licence.master-url", "")
				: proprietes.getMasterUrl();
		return new SourceMaitreHttp(url, () -> {
			CleMaitre c = cle.getIfAvailable();
			return c == null ? Optional.empty() : c.cle();
		}, SourceMaitreHttp.DELAI_CONNEXION, SourceMaitreHttp.DELAI_LECTURE, () -> {
			SurveillanceMetaspace s = surveillance.getIfAvailable();
			return s == null ? null : s.mesure();
		});
	}

}
