package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.logicielpourtous.site.features.commun.TenantContext;
import com.logicielpourtous.site.sdk.ManifestePlugin;
import jakarta.servlet.ServletException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * Routage des plugins : contexte web minimal (cœur) + plugin de test « testnotes » démarré sur la base PostgreSQL
 * locale (schéma jetable {@code plugin_testnotes}).
 */
class RoutagePluginsTest {

	@Configuration
	@EnableWebMvc
	static class CoeurWeb {

		@Bean
		RoutagePlugins routagePlugins() {
			return new RoutagePlugins();
		}

	}

	private Path dossier;

	private DriverManagerDataSource ds;

	private AnnotationConfigWebApplicationContext coeur;

	private RoutagePlugins routage;

	private DemarreurContexte demarreur;

	private MockMvc mvc;

	private final List<ConfigurableApplicationContext> contextes = new ArrayList<>();

	private final List<PluginClassLoader> loaders = new ArrayList<>();

	@BeforeEach
	void preparer() throws Exception {
		dossier = Files.createTempDirectory("routage-test");
		Properties p = new Properties();
		try (InputStream in = Files.newInputStream(Path.of("src/main/resources/application-local.properties"))) {
			p.load(in);
		}
		ds = new DriverManagerDataSource(p.getProperty("spring.datasource.url"),
				p.getProperty("spring.datasource.username"), p.getProperty("spring.datasource.password"));
		nettoyer();
		coeur = new AnnotationConfigWebApplicationContext();
		coeur.setServletContext(new MockServletContext());
		coeur.register(CoeurWeb.class);
		coeur.refresh();
		routage = coeur.getBean(RoutagePlugins.class);
		demarreur = new DemarreurContexte(ds, coeur);
		mvc = MockMvcBuilders.webAppContextSetup(coeur).addFilters(new FiltreEnVol(routage)).build();
	}

	@AfterEach
	void apres() throws Exception {
		try {
			for (ConfigurableApplicationContext c : contextes) {
				c.close();
			}
			for (PluginClassLoader l : loaders) {
				l.close();
			}
			coeur.close();
		}
		finally {
			nettoyer();
			try (Stream<Path> s = Files.walk(dossier)) {
				for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
					Files.delete(p);
				}
			}
		}
	}

	private void nettoyer() {
		new JdbcTemplate(ds).execute("DROP SCHEMA IF EXISTS plugin_testnotes CASCADE");
	}

	/** Génération démarrée du plugin de test (migrations idempotentes entre générations). */
	private PluginGeneration demarrerGeneration(String version) throws Exception {
		Path jar = JarDePluginDeTest.pluginNotes(dossier.resolve("v" + version), version,
				Map.of("V1__note.sql", JarDePluginDeTest.MIGRATION_NOTES_V1));
		ManifestePlugin m = LecteurManifeste.lire(jar);
		Path copie = Files.createTempFile(dossier, "gen-", ".jar");
		Files.copy(jar, copie, StandardCopyOption.REPLACE_EXISTING);
		PluginGeneration gen = new PluginGeneration(m.nom(), m.version(), copie);
		gen.loader = new PluginClassLoader(gen.etiquette(), copie.toUri().toURL(), getClass().getClassLoader());
		loaders.add(gen.loader);
		demarreur.migrer(gen, m);
		contextes.add(demarreur.demarrer(gen, m));
		RoutagePlugins.creerMapping(gen);
		return gen;
	}

	@Test
	void route_vers_le_plugin_actif() throws Exception {
		PluginGeneration gen = demarrerGeneration("1.0.0");
		routage.router("testnotes", gen);

		mvc.perform(get("/api/plugins/testnotes/notes"))
			.andExpect(status().isOk())
			.andExpect(header().string("X-Plugin", gen.etiquette()));
	}

	@Test
	void plugin_inconnu_donne_404() throws Exception {
		mvc.perform(get("/api/plugins/inconnu/notes")).andExpect(status().isNotFound());
	}

	@Test
	void plugin_retire_donne_404() throws Exception {
		PluginGeneration gen = demarrerGeneration("1.0.0");
		routage.router("testnotes", gen);
		routage.retirerRoute("testnotes", gen);

		mvc.perform(get("/api/plugins/testnotes/notes")).andExpect(status().isNotFound());
	}

	@Test
	void maintenance_donne_503_avec_retry_after() throws Exception {
		PluginGeneration gen = demarrerGeneration("1.0.0");
		routage.router("testnotes", gen);

		routage.maintenance("testnotes", true);

		assertThat(routage.enMaintenance("testnotes")).isTrue();
		mvc.perform(get("/api/plugins/testnotes/notes"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(header().string("Retry-After", "2"));
	}

	@Test
	void fin_de_maintenance_redonne_200() throws Exception {
		PluginGeneration gen = demarrerGeneration("1.0.0");
		routage.router("testnotes", gen);
		routage.maintenance("testnotes", true);
		routage.maintenance("testnotes", false);

		assertThat(routage.enMaintenance("testnotes")).isFalse();
		mvc.perform(get("/api/plugins/testnotes/notes")).andExpect(status().isOk());
	}

	@Test
	void bascule_vers_nouvelle_generation_sans_erreur() throws Exception {
		PluginGeneration g1 = demarrerGeneration("1.0.0");
		PluginGeneration g2 = demarrerGeneration("2.0.0");
		routage.router("testnotes", g1);
		mvc.perform(get("/api/plugins/testnotes/notes"))
			.andExpect(status().isOk())
			.andExpect(header().string("X-Plugin", g1.etiquette()));

		routage.router("testnotes", g2);

		mvc.perform(get("/api/plugins/testnotes/notes"))
			.andExpect(status().isOk())
			.andExpect(header().string("X-Plugin", g2.etiquette()));
	}

	@Test
	void en_vol_compte_pendant_la_requete_et_revient_a_zero() throws Exception {
		PluginGeneration gen = demarrerGeneration("1.0.0");
		routage.router("testnotes", gen);

		CompletableFuture<Integer> requete = CompletableFuture.supplyAsync(() -> {
			try {
				return mvc.perform(get("/api/plugins/testnotes/notes/lent").param("ms", "1500"))
					.andReturn()
					.getResponse()
					.getStatus();
			}
			catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (gen.enVol.get() == 0 && System.nanoTime() < limite) {
			Thread.sleep(10);
		}
		assertThat(gen.enVol.get()).isEqualTo(1);

		assertThat(requete.get(10, TimeUnit.SECONDS)).isEqualTo(200);
		assertThat(gen.enVol.get()).isZero();
	}

	@Test
	void en_vol_revient_a_zero_meme_si_le_plugin_leve_une_exception() throws Exception {
		PluginGeneration gen = demarrerGeneration("1.0.0");
		routage.router("testnotes", gen);

		assertThatThrownBy(() -> mvc.perform(get("/api/plugins/testnotes/notes/boom")))
			.isInstanceOf(ServletException.class)
			.hasRootCauseMessage("boom");

		assertThat(gen.enVol.get()).isZero();
	}

	@Test
	void le_coeur_n_a_aucune_route_de_plugin() throws Exception {
		PluginGeneration gen = demarrerGeneration("1.0.0");
		routage.router("testnotes", gen);

		mvc.perform(get("/notes")).andExpect(status().isNotFound());
	}

	@Test
	void sans_generation_annoncee_par_le_filtre_la_requete_n_est_jamais_servie() throws Exception {
		PluginGeneration gen = demarrerGeneration("1.0.0");
		routage.router("testnotes", gen);

		// Fenetre de course : le filtre n'avait trouve aucune generation (marqueur), puis router() a lieu.
		org.springframework.mock.web.MockHttpServletRequest marquee = new org.springframework.mock.web.MockHttpServletRequest(
				"GET", "/api/plugins/testnotes/notes");
		marquee.setAttribute(RoutagePlugins.ATTRIBUT_GENERATION, RoutagePlugins.AUCUNE_GENERATION);
		assertThat(routage.getHandler(marquee)).isNull();

		// Meme resultat sans aucun attribut : le routage ne consulte jamais lui-meme la table des routes.
		assertThat(routage.getHandler(new org.springframework.mock.web.MockHttpServletRequest("GET",
				"/api/plugins/testnotes/notes")))
			.isNull();
		assertThat(gen.enVol.get()).isZero();
	}

	@Test
	void le_contexte_de_tenant_est_nettoye_apres_une_requete_de_plugin() throws Exception {
		PluginGeneration gen = demarrerGeneration("1.0.0");
		routage.router("testnotes", gen);

		TenantContext.setCurrentTenant("schema_fuyant");
		mvc.perform(get("/api/plugins/testnotes/notes")).andExpect(status().isOk());
		assertThat(TenantContext.getCurrentTenant()).isNull();

		TenantContext.setCurrentTenant("schema_fuyant");
		assertThatThrownBy(() -> mvc.perform(get("/api/plugins/testnotes/notes/boom")))
			.isInstanceOf(ServletException.class);
		assertThat(TenantContext.getCurrentTenant()).isNull();
	}

	@Test
	void en_vol_reste_a_un_pendant_un_traitement_asynchrone_puis_revient_a_zero() throws Exception {
		PluginGeneration gen = demarrerGeneration("1.0.0");
		routage.router("testnotes", gen);

		org.springframework.test.web.servlet.MvcResult resultat = mvc
			.perform(get("/api/plugins/testnotes/notes/async").param("ms", "300"))
			.andReturn();

		// Le premier dispatch est termine mais le traitement asynchrone est en cours : toujours en vol.
		assertThat(resultat.getRequest().isAsyncStarted()).isTrue();
		assertThat(gen.enVol.get()).isEqualTo(1);

		resultat.getAsyncResult(10_000);
		mvc.perform(asyncDispatch(resultat)).andExpect(status().isOk());
		// Le conteneur termine le cycle asynchrone apres la redistribution.
		resultat.getRequest().getAsyncContext().complete();

		assertThat(gen.enVol.get()).isZero();
	}

}
