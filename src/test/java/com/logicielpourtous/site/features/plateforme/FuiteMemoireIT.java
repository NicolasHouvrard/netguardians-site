package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * Test de fuite exigé par le spec (4.4 et 8) : après N mises à jour successives d'un plugin servi par le cœur, aucun
 * ancien chargeur de classes ne doit rester vivant (références faibles, après pression mémoire sur les références
 * souples). Le second test prouve que la mesure sait détecter une fuite (nettoyage désactivé).
 */
class FuiteMemoireIT {

	@Configuration
	@EnableWebMvc
	static class CoeurWeb {

		@Bean
		RoutagePlugins routagePlugins() {
			return new RoutagePlugins();
		}

	}

	private static final String V1 = JarDePluginDeTest.MIGRATION_NOTES_V1;

	private static final String V2 = "ALTER TABLE plugin_testnotes.note ADD COLUMN titre text;\n";

	private Path dossier;

	private HikariDataSource ds;

	private JdbcTemplate jdbc;

	private AnnotationConfigWebApplicationContext coeur;

	private PlateformeProperties proprietes;

	private PluginManager manager;

	private MockMvc mvc;

	@BeforeEach
	void preparer() throws Exception {
		dossier = Files.createTempDirectory("fuite-memoire-it");
		Properties p = new Properties();
		try (InputStream in = Files.newInputStream(Path.of("src/main/resources/application-local.properties"))) {
			p.load(in);
		}
		ds = new HikariDataSource();
		ds.setJdbcUrl(p.getProperty("spring.datasource.url"));
		ds.setUsername(p.getProperty("spring.datasource.username"));
		ds.setPassword(p.getProperty("spring.datasource.password"));
		ds.setMaximumPoolSize(4);
		jdbc = new JdbcTemplate(ds);
		jdbc.execute("DROP SCHEMA IF EXISTS plugin_testnotes CASCADE");
		coeur = new AnnotationConfigWebApplicationContext();
		coeur.setServletContext(new MockServletContext());
		coeur.register(CoeurWeb.class);
		coeur.refresh();
		RoutagePlugins routage = coeur.getBean(RoutagePlugins.class);
		proprietes = new PlateformeProperties();
		proprietes.setRepertoirePlugins(dossier.resolve("plugins").toString());
		proprietes.setVersionCoeur("1.0.0");
		proprietes.setDelaiDrainageMs(10_000);
		manager = new PluginManager(proprietes, new DemarreurContexte(ds, coeur), routage, new ContratRegistre(),
				new ReferencesEntrantes(ds), ds, new NettoyeurFuites(coeur, proprietes));
		mvc = MockMvcBuilders.webAppContextSetup(coeur).addFilters(new FiltreEnVol(routage)).build();
	}

	@AfterEach
	void apres() throws Exception {
		try {
			for (String nom : List.copyOf(manager.actifs().keySet())) {
				manager.desinstaller(nom);
			}
			coeur.close();
		}
		finally {
			try {
				jdbc.execute("DROP SCHEMA IF EXISTS plugin_testnotes CASCADE");
				ds.close();
			}
			finally {
				try (Stream<Path> s = Files.walk(dossier)) {
					for (Path f : s.sorted(Comparator.reverseOrder()).toList()) {
						Files.deleteIfExists(f);
					}
				}
			}
		}
	}

	private void installerOk(Path jar) {
		ResultatInstallation r = manager.installer(jar);
		assertThat(r.statut()).as(r.raisons().toString()).isEqualTo(ResultatInstallation.Statut.OK);
	}

	/**
	 * Une requête de chaque sorte qui remplit un cache du cœur : sérialisation JSON d'une entité du plugin, corps JSON
	 * désérialisé, {@code @RequestParam}, et une exception levée par le plugin ({@code exceptionHandlerCache}).
	 */
	private void solliciter() throws Exception {
		assertThat(mvc.perform(post("/api/plugins/testnotes/notes").contentType(MediaType.APPLICATION_JSON)
			.content("{\"texte\":\"n\"}")).andReturn().getResponse().getStatus()).isEqualTo(200);
		assertThat(mvc.perform(get("/api/plugins/testnotes/notes")).andReturn().getResponse().getStatus())
			.isEqualTo(200);
		assertThat(mvc.perform(get("/api/plugins/testnotes/notes/lent").param("ms", "0"))
			.andReturn()
			.getResponse()
			.getStatus()).isEqualTo(200);
		assertThatThrownBy(() -> mvc.perform(get("/api/plugins/testnotes/notes/boom"))).hasRootCauseMessage("boom");
	}

	/** Installe v1 puis enchaîne {@code n} mises à jour alternées v2 / v1, avec des requêtes entre chacune. */
	private void miseAJourEnBoucle(int n) throws Exception {
		Path v1 = JarDePluginDeTest.pluginNotes(dossier.resolve("v1"), "1.0.0", Map.of("V1__note.sql", V1));
		Path v2 = JarDePluginDeTest.pluginNotes(dossier.resolve("v2"), "1.1.0",
				Map.of("V1__note.sql", V1, "V2__titre.sql", V2));
		installerOk(v1);
		solliciter();
		for (int i = 0; i < n; i++) {
			installerOk(i % 2 == 0 ? v2 : v1);
			solliciter();
		}
	}

	@Test
	void vingt_mises_a_jour_aucune_fuite() throws Exception {
		miseAJourEnBoucle(20);

		RapportFuites rapport = manager.rapportFuites(true);

		assertThat(rapport.retires()).isEqualTo(20);
		assertThat(rapport.toujoursVivants()).as("générations encore vivantes : %s", rapport.etiquettesVivantes())
			.isZero();
		assertThat(rapport.etiquettesVivantes()).isEmpty();
		assertThat(rapport.metaspaceMo()).isPositive();
	}

	@Test
	void sans_nettoyage_les_generations_fuient() throws Exception {
		proprietes.setNettoyageActif(false);
		miseAJourEnBoucle(5);

		RapportFuites rapport = manager.rapportFuites(true);

		assertThat(rapport.retires()).isEqualTo(5);
		assertThat(rapport.toujoursVivants()).isPositive();
		assertThat(rapport.etiquettesVivantes()).hasSize(rapport.toujoursVivants());
	}

}
