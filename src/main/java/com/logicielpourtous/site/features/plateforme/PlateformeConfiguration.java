package com.logicielpourtous.site.features.plateforme;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Beans de la plateforme de plugins qui ne sont pas des composants : propriétés et registre de contrats. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.plateforme.actif", havingValue = "true")
@EnableConfigurationProperties(PlateformeProperties.class)
public class PlateformeConfiguration {

	@Bean
	ContratRegistre contratRegistre() {
		return new ContratRegistre();
	}

}
