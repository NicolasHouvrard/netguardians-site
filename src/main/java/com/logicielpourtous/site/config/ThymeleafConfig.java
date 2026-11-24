package com.logicielpourtous.site.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.AbstractConfigurableTemplateResolver;
import org.thymeleaf.templateresolver.ITemplateResolver;
import nz.net.ultraq.thymeleaf.layoutdialect.LayoutDialect;
import org.thymeleaf.extras.springsecurity6.dialect.SpringSecurityDialect;

import com.logicielpourtous.site.features.plateforme.ResolveurGabaritsPlugins;

@Configuration
public class ThymeleafConfig {

    /** Ordre du résolveur du cœur : après celui des plugins (ordre 1), qui ne résout que {@code plugins/<nom>/...}. */
    private static final int ORDRE_NOYAU = 100;

    /**
     * Deux familles de résolveurs : celui des plugins (présent seulement si la plateforme est active), qui ne résout
     * que les noms {@code plugins/<nom>/<chemin>} dans le jar du plugin actif, et celui du cœur (inchangé pour tous
     * les autres noms), enveloppé pour ne jamais résoudre un nom de l'espace {@code plugins/}.
     */
    @Bean
    public SpringTemplateEngine templateEngine(List<ITemplateResolver> resolveurs) {
        SpringTemplateEngine templateEngine = new SpringTemplateEngine();
        for (ITemplateResolver resolveur : resolveurs) {
            if (resolveur instanceof ResolveurGabaritsPlugins) {
                templateEngine.addTemplateResolver(resolveur);
                continue;
            }
            if (resolveur instanceof AbstractConfigurableTemplateResolver configurable
                    && configurable.getOrder() == null) {
                configurable.setOrder(ORDRE_NOYAU);
            }
            templateEngine.addTemplateResolver(ResolveurGabaritsPlugins.sansEspacePlugins(resolveur));
        }

        // 1. Activer le Layout Dialect (pour layout:decorate)
        templateEngine.addDialect(new LayoutDialect());

        // 2. Activer le Security Dialect (pour sec:authorize et sec:authentication)
        // C'est cette ligne qui manquait !
        templateEngine.addDialect(new SpringSecurityDialect());

        return templateEngine;
    }
}
