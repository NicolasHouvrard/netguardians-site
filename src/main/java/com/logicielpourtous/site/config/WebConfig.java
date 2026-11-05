package com.logicielpourtous.site.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import com.logicielpourtous.site.features.commun.TenantInterceptor;
import com.logicielpourtous.site.config.ModuleSecurityInterceptor;

import java.nio.file.Paths;
import java.io.File;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.upload-dir:uploads}")
    private String uploadDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // ── Uploads (fichiers hors JAR) ───────────────────────────────────────
        String absolutePath = new File(uploadDir).getAbsolutePath();
        if (!absolutePath.endsWith(File.separator)) absolutePath += File.separator;
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + absolutePath);

        // ── Angular SPA embarquée dans le JAR sous /app/ ─────────────────────
        // /app et /app/ → index.html ; fichiers statiques servis directement ;
        // toute route Angular inconnue → index.html (SPA client-side routing).
        registry.addResourceHandler("/app/**")
                .addResourceLocations("classpath:/static/app/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location)
                            throws java.io.IOException {
                        Resource resource = super.getResource(resourcePath, location);
                        // Fichier existant → on le sert tel quel (JS, CSS, images…)
                        if (resource != null && resource.exists() && resource.isReadable()) return resource;
                        // Route Angular inconnue → index.html (SPA routing)
                        return location.createRelative("index.html");
                    }
                });
    }

    @Autowired
    private TenantInterceptor tenantInterceptor;

    @Autowired private ModuleSecurityInterceptor moduleSecurityInterceptor;

    @Autowired private PasswordChangeInterceptor passwordChangeInterceptor;

    @Autowired private com.logicielpourtous.site.features.audit.PageVisitInterceptor pageVisitInterceptor;

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatMultipartCustomizer() {
        return factory -> factory.addConnectorCustomizers(connector ->
                connector.setMaxPartCount(-1));
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantInterceptor).order(1)
                .addPathPatterns("/**")
                .excludePathPatterns("/css/**", "/js/**", "/img/**", "/uploads/**",
                                     "/favicon.ico", "/ws/**", "/app/**");
        registry.addInterceptor(moduleSecurityInterceptor).order(2)
                .addPathPatterns("/intra/**");
        registry.addInterceptor(pageVisitInterceptor).order(4)
                .addPathPatterns("/intra/**", "/espace/**", "/admin/**")
                .excludePathPatterns("/api/**");
        registry.addInterceptor(passwordChangeInterceptor).order(3)
                .addPathPatterns("/**")
                .excludePathPatterns("/css/**", "/js/**", "/img/**", "/uploads/**", "/favicon.ico",
                                     "/webjars/**", "/ws/**", "/change-password", "/change-password/**",
                                     "/logout", "/error", "/app/**");
    }
}