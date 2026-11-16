package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.logicielpourtous.site.features.plateforme.ResultatInstallation.Statut;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

/**
 * Vérification de la signature dans {@link PluginManager#installer} : clés de test générées en mémoire et injectées,
 * plugin « testnotes », base PostgreSQL locale (schéma jetable {@code plugin_testnotes}).
 */
class PluginManagerSignatureIT {

	private Path dossier;

	private Path repertoirePlugins;

	private HikariDataSource ds;

	private JdbcTemplate jdbc;

	private AnnotationConfigWebApplicationContext coeur;

	private PlateformeProperties proprietes;

	private PluginManager manager;

	private final Logger journal = (Logger) LoggerFactory.getLogger(PluginManager.class);

	private final ListAppender<ILoggingEvent> capture = new ListAppender<>();

	@BeforeEach
	void preparer() throws Exception {
		dossier = Files.createTempDirectory("plugin-signature-it");
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
		jdbc.execute("DROP SCHEMA IF EXISTS plugin_testnotes CASCADE");
		coeur = new AnnotationConfigWebApplicationContext();
		coeur.setServletContext(new MockServletContext());
		coeur.register(PluginManagerIT.CoeurWeb.class);
		coeur.refresh();
		proprietes = new PlateformeProperties();
		proprietes.setRepertoirePlugins(repertoirePlugins.toString());
		proprietes.setVersionCoeur("1.0.0");
		proprietes.setDelaiDrainageMs(5_000);
		capture.start();
		journal.addAppender(capture);
		manager = gestionnaire();
	}

	private PluginManager gestionnaire() {
		return new PluginManager(proprietes, new ClesPubliques(JarDePluginDeTest.clesDeTest()),
				new DemarreurContexte(ds, coeur), coeur.getBean(RoutagePlugins.class),
				coeur.getBean(ContratRegistre.class), new ReferencesEntrantes(ds), ds,
				new NettoyeurFuites(coeur, proprietes));
	}

	@AfterEach
	void apres() throws Exception {
		journal.detachAppender(capture);
		try {
			for (String nom : List.copyOf(manager.actifs().keySet())) {
				manager.desinstaller(nom);
			}
			assertThat(copiesDeTravail()).isEmpty();
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
						Files.delete(f);
					}
				}
			}
		}
	}

	private Path jarNotes(String sousDossier, String version) {
		return JarDePluginDeTest.pluginNotes(dossier.resolve(sousDossier), version,
				Map.of("V1__note.sql", JarDePluginDeTest.MIGRATION_NOTES_V1));
	}

	private List<Path> copiesDeTravail() throws IOException {
		Path travail = repertoirePlugins.resolve(PluginManager.TRAVAIL);
		if (!Files.isDirectory(travail)) {
			return List.of();
		}
		try (Stream<Path> s = Files.list(travail)) {
			return s.toList();
		}
	}

	private boolean schemaExiste() {
		return jdbc.queryForObject("SELECT count(*) FROM pg_namespace WHERE nspname = 'plugin_testnotes'",
				Integer.class) > 0;
	}

	/** Refus attendu : rien d'actif, aucune copie de travail laissée, aucun schéma créé. */
	private void refusSansTrace(ResultatInstallation r, String raison) throws IOException {
		assertThat(r.statut()).isEqualTo(Statut.REFUSE);
		assertThat(r.raisons()).anyMatch(x -> x.contains(raison));
		assertThat(manager.actifs()).isEmpty();
		assertThat(copiesDeTravail()).isEmpty();
		assertThat(schemaExiste()).isFalse();
	}

	@Test
	void jar_signe_est_installe() throws Exception {
		Path jar = JarDePluginDeTest.signer(jarNotes("v1", "1.0.0"));

		ResultatInstallation r = manager.installer(jar);

		assertThat(r.statut()).as("%s", r.raisons()).isEqualTo(Statut.OK);
		assertThat(manager.actifs()).containsOnlyKeys("testnotes");
		// La génération charge la copie vérifiée, dans le dossier de travail privé, jamais le jar déposé.
		Path copie = manager.actifs().get("testnotes").jarCopie;
		assertThat(copie.getParent()).isEqualTo(repertoirePlugins.resolve(PluginManager.TRAVAIL).toAbsolutePath());
		assertThat(Files.readAllBytes(copie)).isEqualTo(Files.readAllBytes(jar));
	}

	@Test
	void jar_sans_sig_est_refuse() throws Exception {
		ResultatInstallation r = manager.installer(jarNotes("v1", "1.0.0"));

		refusSansTrace(r, "signature absente");
		assertThat(r.plugin()).isNull(); // manifeste non lu : jar non vérifié
	}

	@Test
	void jar_modifie_apres_signature_est_refuse() throws Exception {
		Path jar = JarDePluginDeTest.signer(jarNotes("v1", "1.0.0"));
		byte[] octets = Files.readAllBytes(jar);
		octets[octets.length / 2] ^= 0x01;
		Files.write(jar, octets);

		refusSansTrace(manager.installer(jar), "signature invalide");
	}

	@Test
	void signature_d_un_autre_jar_est_refusee() throws Exception {
		Path v1 = jarNotes("v1", "1.0.0");
		Path v2 = JarDePluginDeTest.signer(jarNotes("v2", "1.1.0"));
		Files.copy(JarDePluginDeTest.sig(v2), JarDePluginDeTest.sig(v1));

		refusSansTrace(manager.installer(v1), "signature invalide");
	}

	@Test
	void cle_inconnue_est_refusee() throws Exception {
		KeyPair autre = JarDePluginDeTest.nouvellePaire();
		Path jar = JarDePluginDeTest.signer(jarNotes("v1", "1.0.0"), autre.getPrivate(), "inconnue");

		refusSansTrace(manager.installer(jar), "clé inconnue");
	}

	@Test
	void cle_d_un_attaquant_sous_un_keyid_de_confiance_est_refusee() throws Exception {
		KeyPair attaquant = JarDePluginDeTest.nouvellePaire();
		Path jar = JarDePluginDeTest.signer(jarNotes("v1", "1.0.0"), attaquant.getPrivate(),
				JarDePluginDeTest.KEY_ID_TEST);

		refusSansTrace(manager.installer(jar), "signature invalide");
	}

	@Test
	void sig_malformee_est_refusee_sans_exception() throws Exception {
		Path jar = jarNotes("v1", "1.0.0");
		for (String contenu : List.of("", "{", "pas du json", "{\"keyId\":\"test-1\"}",
				"{\"keyId\":\"test-1\",\"algo\":\"RSA\",\"signature\":\"AAAA\"}",
				"{\"keyId\":\"test-1\",\"algo\":\"Ed25519\",\"signature\":\"%%%\"}")) {
			Files.writeString(JarDePluginDeTest.sig(jar), contenu, StandardCharsets.UTF_8);

			refusSansTrace(manager.installer(jar), "signature invalide");
		}
		// Octets qui ne sont pas de l'UTF-8 valide.
		Files.write(JarDePluginDeTest.sig(jar), new byte[] { (byte) 0xff, (byte) 0xfe, 0x00 });
		refusSansTrace(manager.installer(jar), "signature");
	}

	@Test
	void sig_trop_volumineuse_est_refusee() throws Exception {
		Path jar = jarNotes("v1", "1.0.0");
		Files.writeString(JarDePluginDeTest.sig(jar), " ".repeat(1024 * 1024), StandardCharsets.UTF_8);

		refusSansTrace(manager.installer(jar), "signature");
	}

	@Test
	void sig_qui_est_un_dossier_est_refusee() throws Exception {
		Path jar = jarNotes("v1", "1.0.0");
		Files.createDirectories(JarDePluginDeTest.sig(jar));

		refusSansTrace(manager.installer(jar), "signature");
	}

	@Test
	void refus_ne_laisse_aucune_copie_dans_work_ni_schema() throws Exception {
		// Un plugin valide est actif : son unique copie de travail doit rester, celles des refus disparaître.
		manager.installer(JarDePluginDeTest.signer(jarNotes("v1", "1.0.0")));
		assertThat(copiesDeTravail()).hasSize(1);
		Path v2 = jarNotes("v2", "1.1.0"); // sans signature
		Path v3 = JarDePluginDeTest.signer(jarNotes("v3", "1.2.0"));
		Files.write(v3, Files.readAllBytes(jarNotes("v3b", "1.2.0"))); // contenu remplacé après signature

		assertThat(manager.installer(v2).statut()).isEqualTo(Statut.REFUSE);
		assertThat(manager.installer(v3).statut()).isEqualTo(Statut.REFUSE);

		assertThat(copiesDeTravail()).hasSize(1);
		assertThat(manager.actifs().get("testnotes").version).isEqualTo("1.0.0");
	}

	@Test
	void jar_trop_volumineux_est_refuse_avant_verification() throws Exception {
		proprietes.setTailleMaxJarMo(1);
		Path jar = dossier.resolve("gros").resolve("testnotes-1.0.0.jar");
		Files.createDirectories(jar.getParent());
		byte[] alea = new byte[2 * 1024 * 1024];
		new Random(42).nextBytes(alea);
		try (OutputStream sortie = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(sortie)) {
			jos.putNextEntry(new JarEntry("plugin.properties"));
			jos.write("nom=testnotes\nversion=1.0.0\n".getBytes(StandardCharsets.UTF_8));
			jos.closeEntry();
			JarEntry gros = new JarEntry("bourrage.bin");
			gros.setMethod(JarEntry.STORED);
			gros.setSize(alea.length);
			gros.setCompressedSize(alea.length);
			java.util.zip.CRC32 crc = new java.util.zip.CRC32();
			crc.update(alea);
			gros.setCrc(crc.getValue());
			jos.putNextEntry(gros);
			jos.write(alea);
			jos.closeEntry();
		}
		JarDePluginDeTest.signer(jar);

		refusSansTrace(manager.installer(jar), "trop volumineux");
	}

	@Test
	void signature_non_obligatoire_installe_un_jar_sans_sig_avec_avertissement() throws Exception {
		proprietes.setSignatureObligatoire(false);
		capture.list.clear();
		manager = gestionnaire();
		assertThat(capture.list).anyMatch(e -> e.getLevel() == Level.WARN
				&& e.getFormattedMessage().contains("signature-obligatoire=false"));

		ResultatInstallation r = manager.installer(jarNotes("v1", "1.0.0"));

		assertThat(r.statut()).as("%s", r.raisons()).isEqualTo(Statut.OK);
		assertThat(manager.actifs()).containsOnlyKeys("testnotes");
		assertThat(capture.list).anyMatch(e -> e.getLevel() == Level.WARN
				&& e.getFormattedMessage().contains("signature non vérifiée"));
	}

	@Test
	void demarrage_ecarte_les_jars_sans_signature_valide_avant_le_choix_de_version() throws Exception {
		// v1 signé ; v2 plus haute mais non signée ; v3 encore plus haute, signée puis modifiée.
		JarDePluginDeTest.signer(JarDePluginDeTest.pluginNotes(repertoirePlugins, "1.0.0",
				Map.of("V1__note.sql", JarDePluginDeTest.MIGRATION_NOTES_V1)));
		JarDePluginDeTest.pluginNotes(repertoirePlugins, "1.1.0",
				Map.of("V1__note.sql", JarDePluginDeTest.MIGRATION_NOTES_V1));
		Path v3 = JarDePluginDeTest.signer(JarDePluginDeTest.pluginNotes(repertoirePlugins, "1.2.0",
				Map.of("V1__note.sql", JarDePluginDeTest.MIGRATION_NOTES_V1)));
		byte[] octets = Files.readAllBytes(v3);
		octets[octets.length / 2] ^= 0x01;
		Files.write(v3, octets);

		manager.demarrer();

		assertThat(manager.actifs()).containsOnlyKeys("testnotes");
		assertThat(manager.actifs().get("testnotes").version).isEqualTo("1.0.0");
		assertThat(capture.list).anyMatch(e -> e.getLevel() == Level.WARN
				&& e.getFormattedMessage().contains("testnotes-1.1.0.jar")
				&& e.getFormattedMessage().contains("signature absente"));
		assertThat(capture.list).anyMatch(e -> e.getLevel() == Level.WARN
				&& e.getFormattedMessage().contains("testnotes-1.2.0.jar")
				&& e.getFormattedMessage().contains("signature invalide"));
	}

	@Test
	void signature_obligatoire_n_avertit_pas_de_jars_non_signes_au_demarrage() {
		assertThat(capture.list).noneMatch(e -> e.getFormattedMessage().contains("signature-obligatoire=false"));
	}

}
