package com.logicielpourtous.site.features.plateforme;

import com.logicielpourtous.site.sdk.ManifestePlugin;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.stereotype.Component;

/**
 * Migre le schéma d'un plugin (Flyway, historique propre) et démarre son contexte Spring enfant (EMF dédiée, non
 * multi-tenant, {@code validate}).
 */
@Component
@ConditionalOnProperty(name = "app.plateforme.actif", havingValue = "true")
public class DemarreurContexte {

	private final DataSource dataSource;

	private final ApplicationContext coeur;

	public DemarreurContexte(DataSource dataSource, ApplicationContext coeur) {
		this.dataSource = dataSource;
		this.coeur = coeur;
	}

	/**
	 * Applique les migrations du plugin dans son schéma {@code plugin_<nom>}. Les migrations en attente forment une
	 * seule transaction : si l'une échoue, PostgreSQL (DDL transactionnel) les annule toutes et l'exception de Flyway
	 * est propagée.
	 */
	public ResultatMigration migrer(PluginGeneration gen, ManifestePlugin m) {
		Flyway flyway = Flyway.configure(gen.loader)
			.dataSource(dataSource)
			.schemas("plugin_" + m.nom())
			.createSchemas(true)
			.table("flyway_schema_history_" + m.nom())
			.locations("classpath:db/plugin/" + m.nom())
			// Toutes les migrations en attente dans UNE transaction : une mise à jour qui en apporte plusieurs est
			// appliquée en entier ou pas du tout (sinon les premières resteraient appliquées après un retour arrière).
			.group(true)
			.load();
		MigrateResult resultat = flyway.migrate();
		return new ResultatMigration(resultat.migrationsExecuted, resultat.targetSchemaVersion);
	}

	/** Démarre le contexte enfant du plugin ; le chargeur de la génération est le chargeur de contexte pendant le refresh. */
	public ConfigurableApplicationContext demarrer(PluginGeneration gen, ManifestePlugin m) {
		AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
		ctx.setId("plugin-" + gen.etiquette());
		ctx.setParent(coeur);
		ctx.setClassLoader(gen.loader);
		ctx.registerBean("entityManagerFactory", LocalContainerEntityManagerFactoryBean.class, () -> {
			LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
			emf.setDataSource(dataSource);
			emf.setPersistenceUnitName("plugin-" + gen.etiquette());
			emf.setPackagesToScan(m.paquet());
			emf.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
			emf.setJpaPropertyMap(Map.of("hibernate.default_schema", "plugin_" + m.nom(), "hibernate.hbm2ddl.auto",
					"validate"));
			return emf;
		});
		ctx.registerBean("transactionManager", JpaTransactionManager.class, () -> new JpaTransactionManager());
		Thread courant = Thread.currentThread();
		ClassLoader precedent = courant.getContextClassLoader();
		courant.setContextClassLoader(gen.loader);
		try {
			ctx.register(gen.loader.loadClass(m.configuration()));
			ctx.refresh();
		}
		catch (ClassNotFoundException e) {
			ctx.close();
			throw new IllegalStateException("Classe de configuration introuvable : " + m.configuration(), e);
		}
		catch (RuntimeException e) {
			ctx.close();
			throw e;
		}
		finally {
			courant.setContextClassLoader(precedent);
		}
		gen.contexte = ctx;
		return ctx;
	}

}
