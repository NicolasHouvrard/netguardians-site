package com.logicielpourtous.site.config;

import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.hibernate.cfg.Environment;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaVendorAdapter;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/**
 * EntityManagerFactory de l'application (multi-tenance par schema).
 *
 * <p><b>Nommage des colonnes :</b> cet EMF est construit a la main et n'applique PAS
 * {@code spring.jpa.hibernate.naming.physical-strategy} (ni la strategie implicite de Boot) :
 * il utilise les strategies par defaut d'Hibernate ({@code PhysicalNamingStrategyStandardImpl}),
 * donc {@code createdAt} -> colonne {@code createdat}. C'est le mapping avec lequel toutes les
 * bases existantes ont ete creees depuis le premier commit (prod comprise) : NE PAS le changer
 * sans migration de donnees, sous peine que {@code ddl-auto=update} cree de nouvelles colonnes
 * vides a cote des anciennes et que l'application ne voie plus les donnees existantes.
 *
 * <p>Les tests {@code @DataJpaTest} doivent importer cette configuration (avec
 * {@code MultiTenantConnectionProviderImpl}/{@code SchemaTenantResolver}) pour ecrire dans les
 * memes colonnes que l'application ; sinon l'EMF auto-configure de Boot (snake_case) ajoute un
 * 2e jeu de colonnes, dont des NOT NULL que l'application ne renseigne jamais.
 */
@Configuration
public class MultiTenantJpaConfiguration {

    @Bean
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(
            DataSource dataSource,
            MultiTenantConnectionProvider<Object> multiTenantConnectionProvider,
            CurrentTenantIdentifierResolver<Object> tenantIdentifierResolver) {

        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(dataSource);
        em.setPackagesToScan("com.logicielpourtous.site");

        HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        em.setJpaVendorAdapter(vendorAdapter);

        Map<String, Object> properties = new HashMap<>();

        // En Hibernate 6, ces clés sont les plus stables :
        properties.put(org.hibernate.cfg.AvailableSettings.MULTI_TENANT_CONNECTION_PROVIDER, multiTenantConnectionProvider);
        properties.put(org.hibernate.cfg.AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, tenantIdentifierResolver);
        
        // On spécifie explicitement que c'est du multi-tenant par schéma
        // Note: Dans certaines versions de Hibernate 6, le réglage se fait via MultiTenancyStrategy
        properties.put("hibernate.multiTenancy", "SCHEMA");

        // Désactiver ddl-auto est CRUCIAL en multi-tenant pour éviter que Hibernate 
        // n'essaie de valider le schéma "public" par rapport à toutes les entités
        properties.put("hibernate.hbm2ddl.auto", "update");

        em.setJpaPropertyMap(properties);
        return em;
    }
}
