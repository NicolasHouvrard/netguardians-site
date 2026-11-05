package com.logicielpourtous.site.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ITemplateResolver;
import nz.net.ultraq.thymeleaf.layoutdialect.LayoutDialect;
import org.thymeleaf.extras.springsecurity6.dialect.SpringSecurityDialect;

@Configuration
public class ThymeleafConfig {

    @Bean
    public SpringTemplateEngine templateEngine(ITemplateResolver templateResolver) {
        SpringTemplateEngine templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(templateResolver);
        
        // 1. Activer le Layout Dialect (pour layout:decorate)
        templateEngine.addDialect(new LayoutDialect());
        
        // 2. Activer le Security Dialect (pour sec:authorize et sec:authentication)
        // C'est cette ligne qui manquait !
        templateEngine.addDialect(new SpringSecurityDialect());
        
        return templateEngine;
    }
}