package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * Gestionnaire de plugins de bout en bout : contexte web minimal (cœur), plugin de test « testnotes » en plusieurs
 * versions, base PostgreSQL locale (schéma jetable {@code plugin_testnotes}, supprimé après chaque test).
 */
class PluginManagerIT {

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

	private static final String V3_CASSE = "ALTER TABLE plugin_testnotes.note ADD COLUMN extra INT;\n"
			+ "ALTER TABLE inexistante ADD COLUMN y INT;\n";

	private static final String V3_DROP = "ALTER TABLE plugin_testnotes.note DROP COLUMN titre;\n";

	private Path dossier;

	private Path repertoirePlugins;

	private HikariDataSource ds;

	private JdbcTemplate jdbc;

	private AnnotationConfigWebApplicationContext coeur;

	private RoutagePlugins routage;

	private PlateformeProperties proprietes;

	private PluginManager manager;

	private ContratRegistre contrats;

	private DemarreurPiege demarreur;

	private MockMvc mvc;

	/** Démarreur qui lève une erreur choisie au démarrage du contexte d'une version donnée. */
	static class DemarreurPiege extends DemarreurContexte {

		volatile String versionPiegee;

		volatile Error erreur;

		DemarreurPiege(javax.sql.DataSource ds, org.springframework.context.ApplicationContext coeur) {
			super(ds, coeur);
		}

		@Override
		public org.springframework.context.ConfigurableApplicationContext demarrer(PluginGeneration gen,
				com.logicielpourtous.site.sdk.ManifestePlugin m) {
			if (gen.version.equals(versionPiegee)) {
				throw erreur;
			}
			return super.demarrer(gen, m);
		}

	}

	@BeforeEach
	void preparer() throws Exception {
		dossier = Files.createTempDirectory("plugin-manager-it");
		repertoirePlugins = dossier.resolve("plugins");
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
		nettoyerBase();
		coeur = new AnnotationConfigWebApplicationContext();
		coeur.setServletContext(new MockServletContext());
		coeur.register(CoeurWeb.class);
		coeur.refresh();
		routage = coeur.getBean(RoutagePlugins.class);
		proprietes = new PlateformeProperties();
		proprietes.setRepertoirePlugins(repertoirePlugins.toString());
		proprietes.setVersionCoeur("1.0.0");
		proprietes.setDelaiDrainageMs(10_000);
		contrats = new ContratRegistre();
		demarreur = new DemarreurPiege(ds, coeur);
		manager = new PluginManager(proprietes, demarreur, routage, contrats, new ReferencesEntrantes(ds), ds,
				new NettoyeurFuites(coeur, proprietes));
		mvc = MockMvcBuilders.webAppContextSetup(coeur).addFilters(new FiltreEnVol(routage)).build();
	}

	@AfterEach
	void apres() throws Exception {
		try {
			for (String nom : List.copyOf(manager.actifs().keySet())) {
				manager.desinstaller(nom);
			}
			assertThat(manager.actifs()).isEmpty();
			// Chaque copie de jar chargée doit avoir été supprimée à la fermeture de sa génération.
			assertThat(copiesDeTravail()).isEmpty();
			coeur.close();
		}
		finally {
			try {
				nettoyerBase();
				ds.close();
			}
			finally {
				supprimerDossier();
			}
		}
	}

	private void nettoyerBase() {
		jdbc.execute("DROP SCHEMA IF EXISTS plugin_testnotes CASCADE");
		jdbc.execute("DROP SCHEMA IF EXISTS plugin_dependant CASCADE");
		jdbc.execute("DROP SCHEMA IF EXISTS plugin_testfourni CASCADE");	}

	/** Plugin « testfourni » sans table, dont un bean implémente {@link ContratDeTest} (réponse « v<version> nom »). */
	private Path pluginFournisseur(String version) {
		return JarDePluginDeTest.creer(dossier.resolve("fourni-" + version), "testfourni", version,
				Map.of("coeur-min", "0.0.0", "mise-a-jour", "chaud", "paquet", "fourni", "configuration",
						"fourni.FourniConfiguration"),
				Map.of(),
				Map.of("fourni.FourniConfiguration", """
						package fourni;
						@org.springframework.context.annotation.Configuration
						@org.springframework.context.annotation.ComponentScan("fourni")
						public class FourniConfiguration {
						}
						""", "fourni.Salueur", """
						package fourni;
						@org.springframework.stereotype.Component
						public class Salueur implements com.logicielpourtous.site.features.plateforme.ContratDeTest {
						    public String saluer(String nom) { return "v%s " + nom; }
						}
						""".formatted(version)));
	}

	/** Suppression réelle (pas « au mieux ») : un jar encore verrouillé ferait échouer le test. */
	private void supprimerDossier() throws IOException {
		try (Stream<Path> s = Files.walk(dossier)) {
			for (Path f : s.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(f);
			}
		}
	}

	private List<Path> copiesDeTravail() throws IOException {
		Path travail = repertoirePlugins.resolve(".work");
		if (!Files.isDirectory(travail)) {
			return List.of();
		}
		try (Stream<Path> s = Files.list(travail)) {
			return s.toList();
		}
	}

	private Path jar(String sousDossier, String version, Map<String, String> migrations) {
		return JarDePluginDeTest.pluginNotes(dossier.resolve(sousDossier), version, migrations);
	}

	private Path jarV1() {
		return jar("v1", "1.0.0", Map.of("V1__note.sql", V1));
	}

	private Path jarV2() {
		return jar("v2", "1.1.0", Map.of("V1__note.sql", V1, "V2__titre.sql", V2));
	}

	private Path jarV3Casse() {
		return jar("v3-casse", "1.2.0", Map.of("V1__note.sql", V1, "V2__titre.sql", V2, "V3__casse.sql", V3_CASSE));
	}

	private Path jarV3CoeurRequis() {
		return jar("v3-coeur", "1.2.0", Map.of("V1__note.sql", V1, "V2__titre.sql", V2, "V3__drop.sql", V3_DROP));
	}

	private MockHttpServletResponse lister() throws Exception {
		return mvc.perform(get("/api/plugins/testnotes/notes")).andReturn().getResponse();
	}

	private int compter(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	private boolean colonne(String nom) {
		return compter("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'plugin_testnotes' "
				+ "AND table_name = 'note' AND column_name = ?", nom) == 1;
	}

	private void installerOk(Path jar) {
		ResultatInstallation r = manager.installer(jar);
		assertThat(r.statut()).as(r.raisons().toString()).isEqualTo(ResultatInstallation.Statut.OK);
	}

	@Test
	void installation_initiale_puis_requete_ok() throws Exception {
		ResultatInstallation r = manager.installer(jarV1());

		assertThat(r.statut()).as(r.raisons().toString()).isEqualTo(ResultatInstallation.Statut.OK);
		assertThat(r.plugin()).isEqualTo("testnotes");
		assertThat(r.version()).isEqualTo("1.0.0");
		assertThat(r.raisons()).isEmpty();
		assertThat(r.interruptionMs()).isZero();
		assertThat(manager.actifs()).containsOnlyKeys("testnotes");
		MockHttpServletResponse reponse = lister();
		assertThat(reponse.getStatus()).isEqualTo(200);
		assertThat(reponse.getHeader("X-Plugin")).startsWith("testnotes@1.0.0#");
		assertThat(routage.enMaintenance("testnotes")).isFalse();
		assertThat(copiesDeTravail()).hasSize(1);
	}

	@Test
	void mise_a_jour_v1_vers_v2_sous_trafic() throws Exception {
		installerOk(jarV1());
		Path v2 = jarV2();
		AtomicBoolean arreter = new AtomicBoolean();
		ConcurrentLinkedQueue<String> anomalies = new ConcurrentLinkedQueue<>();
		List<Integer> statuts = java.util.Collections.synchronizedList(new ArrayList<>());
		CompletableFuture<Void> trafic = CompletableFuture.runAsync(() -> {
			while (!arreter.get()) {
				try {
					MockHttpServletResponse rep = lister();
					statuts.add(rep.getStatus());
					if (rep.getStatus() == 503 && rep.getHeader("Retry-After") == null) {
						anomalies.add("503 sans Retry-After");
					}
					else if (rep.getStatus() != 200 && rep.getStatus() != 503) {
						anomalies.add("statut " + rep.getStatus());
					}
				}
				catch (Exception e) {
					anomalies.add("exception " + e);
				}
			}
		});
		Thread.sleep(200);

		ResultatInstallation r = manager.installer(v2);
		Thread.sleep(200);
		arreter.set(true);
		trafic.get(30, TimeUnit.SECONDS);

		assertThat(r.statut()).as(r.raisons().toString()).isEqualTo(ResultatInstallation.Statut.OK);
		assertThat(r.version()).isEqualTo("1.1.0");
		assertThat(r.interruptionMs()).isPositive().isLessThan(5000);
		assertThat(anomalies).isEmpty();
		assertThat(statuts).contains(200);
		MockHttpServletResponse reponse = lister();
		assertThat(reponse.getStatus()).isEqualTo(200);
		assertThat(reponse.getHeader("X-Plugin")).startsWith("testnotes@1.1.0#");
		assertThat(routage.enMaintenance("testnotes")).isFalse();
		assertThat(copiesDeTravail()).hasSize(1);
	}

	@Test
	void migration_additive_v2_acceptee_a_chaud() throws Exception {
		installerOk(jarV1());

		ResultatInstallation r = manager.installer(jarV2());

		assertThat(r.statut()).as(r.raisons().toString()).isEqualTo(ResultatInstallation.Statut.OK);
		assertThat(r.raisons()).isEmpty();
		assertThat(colonne("titre")).isTrue();
	}

	@Test
	void donnees_v1_lisibles_par_v2() throws Exception {
		installerOk(jarV1());
		mvc.perform(post("/api/plugins/testnotes/notes").contentType(MediaType.APPLICATION_JSON)
			.content("{\"texte\":\"ecrite par v1\"}")).andReturn();
		assertThat(compter("SELECT count(*) FROM plugin_testnotes.note")).isEqualTo(1);

		installerOk(jarV2());

		MockHttpServletResponse reponse = lister();
		assertThat(reponse.getStatus()).isEqualTo(200);
		assertThat(reponse.getHeader("X-Plugin")).startsWith("testnotes@1.1.0#");
		assertThat(reponse.getContentAsString()).contains("ecrite par v1");
	}

	/** v1 -> v3-casse : V2 (valide) et V3 (cassée) sont en attente ensemble ; aucune des deux ne doit rester appliquée. */
	@Test
	void migration_cassee_annule_et_ancienne_version_sert() throws Exception {
		installerOk(jarV1());
		List<String> historiqueAvant = jdbc.queryForList(
				"SELECT version || ':' || success FROM plugin_testnotes.flyway_schema_history_testnotes ORDER BY installed_rank",
				String.class);

		ResultatInstallation r = manager.installer(jarV3Casse());

		assertThat(r.statut()).isEqualTo(ResultatInstallation.Statut.ANNULE);
		assertThat(r.version()).isEqualTo("1.2.0");
		assertThat(r.raisons()).isNotEmpty();
		assertThat(r.interruptionMs()).isPositive();
		assertThat(routage.enMaintenance("testnotes")).isFalse();
		MockHttpServletResponse reponse = lister();
		assertThat(reponse.getStatus()).isEqualTo(200);
		assertThat(reponse.getHeader("X-Plugin")).startsWith("testnotes@1.0.0#");
		assertThat(manager.actifs().get("testnotes").version).isEqualTo("1.0.0");
		assertThat(jdbc.queryForList(
				"SELECT version || ':' || success FROM plugin_testnotes.flyway_schema_history_testnotes ORDER BY installed_rank",
				String.class))
			.isEqualTo(historiqueAvant);
		assertThat(colonne("extra")).isFalse();
		assertThat(colonne("titre")).isFalse();
		assertThat(copiesDeTravail()).hasSize(1);
	}

	@Test
	void declaration_chaud_contredite_refusee() throws Exception {
		installerOk(jarV1());
		installerOk(jarV2());

		ResultatInstallation r = manager.installer(jarV3CoeurRequis());

		assertThat(r.statut()).isEqualTo(ResultatInstallation.Statut.REFUSE);
		assertThat(r.raisons()).isNotEmpty();
		assertThat(r.raisons()).anyMatch(raison -> raison.contains("V3__drop.sql"));
		assertThat(r.raisons()).noneMatch(raison -> raison.contains("V1__") || raison.contains("V2__"));
		assertThat(r.interruptionMs()).isZero();
		assertThat(manager.actifs().get("testnotes").version).isEqualTo("1.1.0");
		assertThat(lister().getHeader("X-Plugin")).startsWith("testnotes@1.1.0#");
		assertThat(colonne("titre")).isTrue();
	}

	@Test
	void requete_plus_longue_que_le_drainage_est_forcee() throws Exception {
		proprietes.setDelaiDrainageMs(300);
		installerOk(jarV1());
		Path v2 = jarV2();
		PluginGeneration v1 = manager.actifs().get("testnotes");
		Logger journal = (Logger) LoggerFactory.getLogger(PluginManager.class);
		ListAppender<ILoggingEvent> capture = new ListAppender<>();
		capture.start();
		journal.addAppender(capture);
		try {
			CompletableFuture<Integer> lente = CompletableFuture.supplyAsync(() -> {
				try {
					return mvc.perform(get("/api/plugins/testnotes/notes/lent").param("ms", "2000"))
						.andReturn()
						.getResponse()
						.getStatus();
				}
				catch (Exception e) {
					return -1;
				}
			});
			long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
			while (v1.enVol.get() == 0 && System.nanoTime() < limite) {
				Thread.sleep(5);
			}
			assertThat(v1.enVol.get()).isEqualTo(1);
			long debut = System.nanoTime();

			ResultatInstallation r = manager.installer(v2);

			long dureeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - debut);
			assertThat(r.statut()).as(r.raisons().toString()).isEqualTo(ResultatInstallation.Statut.OK);
			assertThat(dureeMs).isLessThan(2000);
			assertThat(capture.list).anyMatch(e -> e.getLevel() == Level.WARN
					&& e.getFormattedMessage().contains("fermeture forcée")
					&& e.getFormattedMessage().contains("testnotes@1.0.0"));
			lente.get(10, TimeUnit.SECONDS);
		}
		finally {
			journal.detachAppender(capture);
		}
	}

	@Test
	void deux_installations_simultanees_sont_serialisees() throws Exception {
		Path v1 = jarV1();
		Path v2 = jarV2();
		CountDownLatch depart = new CountDownLatch(1);
		CompletableFuture<ResultatInstallation> a = CompletableFuture.supplyAsync(() -> {
			attendre(depart);
			return manager.installer(v1);
		});
		CompletableFuture<ResultatInstallation> b = CompletableFuture.supplyAsync(() -> {
			attendre(depart);
			return manager.installer(v2);
		});

		depart.countDown();
		ResultatInstallation ra = a.get(60, TimeUnit.SECONDS);
		ResultatInstallation rb = b.get(60, TimeUnit.SECONDS);

		assertThat(ra.statut()).as(ra.raisons().toString()).isEqualTo(ResultatInstallation.Statut.OK);
		assertThat(rb.statut()).as(rb.raisons().toString()).isEqualTo(ResultatInstallation.Statut.OK);
		assertThat(manager.actifs()).hasSize(1);
		PluginGeneration active = manager.actifs().get("testnotes");
		assertThat(routage.generationActive("testnotes")).isSameAs(active);
		assertThat(copiesDeTravail()).hasSize(1);
		assertThat(lister().getHeader("X-Plugin")).isEqualTo(active.etiquette());
	}

	private static void attendre(CountDownLatch latch) {
		try {
			latch.await();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Test
	void repertoire_avec_fichier_non_jar_et_jar_invalide_ne_bloque_pas_le_demarrage() throws Exception {
		Files.createDirectories(repertoirePlugins.resolve(".work"));
		Files.writeString(repertoirePlugins.resolve("lisezmoi.txt"), "pas un plugin");
		Files.writeString(repertoirePlugins.resolve("a-invalide.jar"), "ceci n'est pas un zip");
		try (JarOutputStream vide = new JarOutputStream(
				Files.newOutputStream(repertoirePlugins.resolve("b-sans-manifeste.jar")))) {
			// jar valide mais sans plugin.properties
		}
		Files.copy(JarDePluginDeTest.creer(dossier.resolve("nom-invalide"), "Nom-Invalide", "1.0.0",
				Map.of("coeur-min", "0.0.0", "mise-a-jour", "chaud", "paquet", "x", "configuration", "x.C"), Map.of()),
				repertoirePlugins.resolve("c-nom-invalide.jar"));
		Files.copy(jarV1(), repertoirePlugins.resolve("testnotes-1.0.0.jar"));
		Files.writeString(repertoirePlugins.resolve(".work").resolve("reste-d-un-arret-brutal.jar"), "vieux");

		manager.demarrer();

		assertThat(manager.actifs()).containsOnlyKeys("testnotes");
		assertThat(lister().getStatus()).isEqualTo(200);
		// Les restes de .work sont purgés ; seule la copie de la génération active demeure.
		assertThat(copiesDeTravail()).hasSize(1)
			.noneMatch(f -> f.getFileName().toString().equals("reste-d-un-arret-brutal.jar"));
	}

	@Test
	void nom_invalide_refuse() throws Exception {
		Path jar = JarDePluginDeTest.creer(dossier.resolve("nom-invalide"), "Mauvais-Nom", "1.0.0",
				Map.of("coeur-min", "0.0.0", "mise-a-jour", "chaud", "paquet", "x", "configuration", "x.C"), Map.of());

		ResultatInstallation r = manager.installer(jar);

		assertThat(r.statut()).isEqualTo(ResultatInstallation.Statut.REFUSE);
		assertThat(r.raisons()).anyMatch(raison -> raison.contains("nom"));
		assertThat(manager.actifs()).isEmpty();
		assertThat(copiesDeTravail()).isEmpty();
	}

	@Test
	void changement_majeur_force_la_mise_a_jour_du_coeur() throws Exception {
		installerOk(jarV1());

		ResultatInstallation r = manager
			.installer(jar("v2-majeur", "2.0.0", Map.of("V1__note.sql", V1, "V2__titre.sql", V2)));

		assertThat(r.statut()).isEqualTo(ResultatInstallation.Statut.REFUSE);
		assertThat(r.raisons()).anyMatch(raison -> raison.contains("majeure") && raison.contains("mise à jour du cœur"));
		assertThat(manager.actifs().get("testnotes").version).isEqualTo("1.0.0");
		assertThat(colonne("titre")).isFalse();
	}

	@Test
	void dependance_absente_ou_trop_ancienne_refusee() throws Exception {
		Path dependant = JarDePluginDeTest.creer(dossier.resolve("dependant"), "dependant", "1.0.0",
				Map.of("coeur-min", "0.0.0", "mise-a-jour", "chaud", "paquet", "dep", "configuration", "dep.C",
						"dependances", "testnotes>=1.1.0"),
				Map.of());

		ResultatInstallation absente = manager.installer(dependant);

		assertThat(absente.statut()).isEqualTo(ResultatInstallation.Statut.REFUSE);
		assertThat(absente.raisons()).anyMatch(raison -> raison.contains("testnotes") && raison.contains("absent"));

		installerOk(jarV1());
		ResultatInstallation ancienne = manager.installer(dependant);

		assertThat(ancienne.statut()).isEqualTo(ResultatInstallation.Statut.REFUSE);
		assertThat(ancienne.raisons()).anyMatch(raison -> raison.contains("testnotes") && raison.contains("1.0.0"));
		assertThat(manager.actifs()).containsOnlyKeys("testnotes");
		assertThat(compter("SELECT count(*) FROM pg_namespace WHERE nspname = 'plugin_dependant'")).isZero();
	}

	@Test
	void contrat_fourni_avant_toute_demande_puis_mis_a_jour_a_chaud() throws Exception {
		installerOk(pluginFournisseur("1.0.0"));

		// Consommateur arrivé après le fournisseur : personne n'avait demandé le type avant l'installation.
		ContratDeTest proxy = contrats.obtenir(ContratDeTest.class);

		assertThat(proxy.saluer("x")).isEqualTo("v1.0.0 x");

		installerOk(pluginFournisseur("1.1.0"));

		assertThat(contrats.obtenir(ContratDeTest.class)).isSameAs(proxy);
		assertThat(proxy.saluer("x")).isEqualTo("v1.1.0 x");
		assertThat(manager.actifs().get("testfourni").enVol.get()).isZero();
	}

	@Test
	void migration_hors_v_de_premier_niveau_refusee() throws Exception {
		Path repetable = JarDePluginDeTest.pluginNotes(dossier.resolve("repetable"), "1.0.0",
				Map.of("V1__note.sql", V1, "R__vues.sql", "CREATE OR REPLACE VIEW plugin_testnotes.v AS SELECT 1;"));
		Path sousDossier = JarDePluginDeTest.pluginNotes(dossier.resolve("sous-dossier"), "1.0.0",
				Map.of("V1__note.sql", V1, "x/V3__drop.sql", "DROP TABLE plugin_testnotes.note;"));

		for (Path jar : List.of(repetable, sousDossier)) {
			ResultatInstallation r = manager.installer(jar);

			assertThat(r.statut()).isEqualTo(ResultatInstallation.Statut.REFUSE);
			assertThat(r.raisons()).anyMatch(raison -> raison.contains("R__vues.sql") || raison.contains("x/V3__drop.sql"));
		}
		assertThat(manager.actifs()).isEmpty();
		assertThat(copiesDeTravail()).isEmpty();
		assertThat(compter("SELECT count(*) FROM pg_namespace WHERE nspname = 'plugin_testnotes'")).isZero();
	}

	@Test
	void erreur_de_liaison_pendant_la_mise_a_jour_relance_l_ancienne_version() throws Exception {
		installerOk(jarV1());
		demarreur.versionPiegee = "1.1.0";
		demarreur.erreur = new NoClassDefFoundError("classe/Simulee");

		ResultatInstallation r = manager.installer(jarV2());

		assertThat(r.statut()).isEqualTo(ResultatInstallation.Statut.ANNULE);
		assertThat(r.raisons()).anyMatch(raison -> raison.contains("classe/Simulee"));
		assertThat(routage.enMaintenance("testnotes")).isFalse();
		assertThat(lister().getHeader("X-Plugin")).startsWith("testnotes@1.0.0#");
		assertThat(copiesDeTravail()).hasSize(1);
	}

	@Test
	void erreur_de_liaison_a_la_premiere_installation_nettoie_la_copie() throws Exception {
		demarreur.versionPiegee = "1.0.0";
		demarreur.erreur = new NoClassDefFoundError("classe/Simulee");

		ResultatInstallation r = manager.installer(jarV1());

		assertThat(r.statut()).isEqualTo(ResultatInstallation.Statut.ECHEC);
		assertThat(manager.actifs()).isEmpty();
		assertThat(copiesDeTravail()).isEmpty();
	}

	@Test
	void erreur_inattendue_au_demarrage_ne_bloque_pas_les_autres_jars() throws Exception {
		Files.createDirectories(repertoirePlugins);
		Files.copy(jar("v0", "0.9.0", Map.of("V1__note.sql", V1)), repertoirePlugins.resolve("a-testnotes-0.9.0.jar"));
		Files.copy(jarV1(), repertoirePlugins.resolve("b-testnotes-1.0.0.jar"));
		demarreur.versionPiegee = "0.9.0";
		demarreur.erreur = new AssertionError("erreur inattendue simulée");

		manager.demarrer();

		assertThat(manager.actifs()).containsOnlyKeys("testnotes");
		assertThat(manager.actifs().get("testnotes").version).isEqualTo("1.0.0");
		assertThat(lister().getStatus()).isEqualTo(200);
		assertThat(copiesDeTravail()).hasSize(1);
	}

	@Test
	void desinstaller_retire_la_route_et_conserve_le_schema() throws Exception {
		installerOk(jarV1());

		assertThat(manager.desinstaller("testnotes")).isTrue();

		assertThat(manager.actifs()).isEmpty();
		assertThat(lister().getStatus()).isEqualTo(404);
		assertThat(compter("SELECT count(*) FROM pg_namespace WHERE nspname = 'plugin_testnotes'")).isEqualTo(1);
		assertThat(copiesDeTravail()).isEmpty();
		assertThat(manager.desinstaller("testnotes")).isFalse();
	}

}
