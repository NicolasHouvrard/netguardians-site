package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logicielpourtous.site.sdk.ManifestePlugin;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.data.repository.CrudRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.EntityManagerFactoryInfo;

/** Test avec la base PostgreSQL locale (schéma jetable {@code plugin_testnotes}). */
class DemarreurContexteIT {

	private Path dossier;

	private DriverManagerDataSource ds;

	private GenericApplicationContext coeur;

	private DemarreurContexte demarreur;

	private final List<ConfigurableApplicationContext> contextes = new ArrayList<>();

	private final List<PluginClassLoader> loaders = new ArrayList<>();

	@BeforeEach
	void preparer() throws Exception {
		dossier = Files.createTempDirectory("demarreur-it");
		Properties p = new Properties();
		try (InputStream in = Files.newInputStream(Path.of("src/main/resources/application-local.properties"))) {
			p.load(in);
		}
		ds = new DriverManagerDataSource(p.getProperty("spring.datasource.url"),
				p.getProperty("spring.datasource.username"), p.getProperty("spring.datasource.password"));
		nettoyer();
		coeur = new GenericApplicationContext();
		coeur.getBeanFactory().registerSingleton("dataSource", ds);
		coeur.refresh();
		demarreur = new DemarreurContexte(ds, coeur);
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
			supprimerDossier();
		}
	}

	/** Un jar chargé reste parfois verrouillé sous Windows : suppression au mieux, sans faire échouer le test. */
	private void supprimerDossier() {
		try (java.util.stream.Stream<Path> s = Files.walk(dossier)) {
			s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		}
		catch (java.io.IOException ignore) {
		}
	}

	private void nettoyer() {
		new JdbcTemplate(ds).execute("DROP SCHEMA IF EXISTS plugin_testnotes CASCADE");
	}

	private PluginGeneration generation(Path jar) throws Exception {
		ManifestePlugin m = LecteurManifeste.lire(jar);
		Path copie = Files.createTempFile(dossier, "gen-", ".jar");
		Files.copy(jar, copie, StandardCopyOption.REPLACE_EXISTING);
		PluginGeneration gen = new PluginGeneration(m.nom(), m.version(), copie);
		gen.loader = new PluginClassLoader(gen.etiquette(), copie.toUri().toURL(), getClass().getClassLoader());
		loaders.add(gen.loader);
		return gen;
	}

	private int compter(String sql, Object... args) {
		return new JdbcTemplate(ds).queryForObject(sql, Integer.class, args);
	}

	private Path pluginV1(Path rep) {
		return JarDePluginDeTest.pluginNotes(rep, "1.0.0", Map.of("V1__note.sql", JarDePluginDeTest.MIGRATION_NOTES_V1));
	}

	@Test
	void migre_dans_le_schema_du_plugin() throws Exception {
		Path jar = pluginV1(dossier);
		PluginGeneration gen = generation(jar);

		ResultatMigration r = demarreur.migrer(gen, LecteurManifeste.lire(jar));

		assertThat(r.appliquees()).isEqualTo(1);
		assertThat(r.versionSchema()).isEqualTo("1");
		String sql = "SELECT count(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?";
		assertThat(compter(sql, "plugin_testnotes", "note")).isEqualTo(1);
		assertThat(compter(sql, "plugin_testnotes", "flyway_schema_history_testnotes")).isEqualTo(1);
		assertThat(compter(
				"SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'note'"))
			.isZero();
	}

	@Test
	void demarre_un_contexte_qui_lit_et_ecrit() throws Exception {
		Path jar = pluginV1(dossier);
		PluginGeneration gen = generation(jar);
		ManifestePlugin m = LecteurManifeste.lire(jar);
		demarreur.migrer(gen, m);
		ClassLoader avant = Thread.currentThread().getContextClassLoader();

		ConfigurableApplicationContext ctx = demarreur.demarrer(gen, m);
		contextes.add(ctx);

		assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(avant);
		assertThat(ctx.getClassLoader()).isSameAs(gen.loader);
		@SuppressWarnings("unchecked")
		CrudRepository<Object, Long> depot = ctx.getBean("noteRepository", CrudRepository.class);
		Class<?> note = gen.loader.loadClass("notes.Note");
		Object cree = depot.save(note.getConstructor(String.class).newInstance("bonjour"));
		Long id = (Long) note.getMethod("getId").invoke(cree);
		assertThat(id).isNotNull();
		Object relu = depot.findById(id).orElseThrow();
		assertThat(note.getMethod("getTexte").invoke(relu)).isEqualTo("bonjour");
		assertThat(compter("SELECT count(*) FROM plugin_testnotes.note")).isEqualTo(1);
	}

	@Test
	void migration_cassee_ne_laisse_aucune_trace() throws Exception {
		Path jar1 = pluginV1(dossier.resolve("v1"));
		demarreur.migrer(generation(jar1), LecteurManifeste.lire(jar1));

		Path jar2 = JarDePluginDeTest.pluginNotes(dossier.resolve("v2"), "2.0.0",
				Map.of("V1__note.sql", JarDePluginDeTest.MIGRATION_NOTES_V1, "V2__casse.sql",
						"ALTER TABLE plugin_testnotes.note ADD COLUMN extra INT;\nALTER TABLE inexistante ADD COLUMN y INT;\n"));
		assertThatThrownBy(() -> demarreur.migrer(generation(jar2), LecteurManifeste.lire(jar2)))
			.isInstanceOf(Exception.class);

		assertThat(compter("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'plugin_testnotes' "
				+ "AND table_name = 'note' AND column_name = 'extra'"))
			.isZero();
		assertThat(compter("SELECT count(*) FROM plugin_testnotes.flyway_schema_history_testnotes WHERE version = '2'"))
			.isZero();
		assertThat(compter(
				"SELECT count(*) FROM plugin_testnotes.flyway_schema_history_testnotes WHERE version = '1' AND success"))
			.isEqualTo(1);
	}

	@Test
	void deux_generations_ont_des_unites_de_persistance_distinctes() throws Exception {
		Path jar = pluginV1(dossier);
		ManifestePlugin m = LecteurManifeste.lire(jar);
		PluginGeneration g1 = generation(jar);
		PluginGeneration g2 = generation(jar);
		demarreur.migrer(g1, m);

		ConfigurableApplicationContext c1 = demarreur.demarrer(g1, m);
		contextes.add(c1);
		ConfigurableApplicationContext c2 = demarreur.demarrer(g2, m);
		contextes.add(c2);

		String u1 = ((EntityManagerFactoryInfo) c1.getBean("entityManagerFactory")).getPersistenceUnitName();
		String u2 = ((EntityManagerFactoryInfo) c2.getBean("entityManagerFactory")).getPersistenceUnitName();
		assertThat(u1).isEqualTo("plugin-" + g1.etiquette());
		assertThat(u2).isEqualTo("plugin-" + g2.etiquette());
		assertThat(u1).isNotEqualTo(u2);
		assertThat(c1.getBean("noteRepository")).isNotSameAs(c2.getBean("noteRepository"));
	}

}
