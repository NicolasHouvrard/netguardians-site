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
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
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
import org.junit.jupiter.api.Assumptions;
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

	/** Démarreur qui, pour {@link #versionPiegee}, exécute {@link #piege} puis échoue (déclenche le retour arrière). */
	private final class DemarreurPiege extends DemarreurContexte {

		volatile String versionPiegee;

		volatile Runnable piege;

		DemarreurPiege() {
			super(ds, coeur);
		}

		@Override
		public org.springframework.context.ConfigurableApplicationContext demarrer(PluginGeneration gen,
				com.logicielpourtous.site.sdk.ManifestePlugin m) {
			if (gen.version.equals(versionPiegee)) {
				piege.run();
				throw new IllegalStateException("démarrage piégé");
			}
			return super.demarrer(gen, m);
		}

	}

	private DemarreurPiege demarreur;

	private PluginManager gestionnaire() {
		demarreur = new DemarreurPiege();
		return new PluginManager(proprietes, new ClesPubliques(JarDePluginDeTest.clesDeTest()),
				demarreur, coeur.getBean(RoutagePlugins.class),
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
		Path travail = manager.repertoireTravail();
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
		assertThat(copie.getParent()).isEqualTo(manager.repertoireTravail());
		assertThat(manager.repertoireTravail()).isEqualTo(dossier.resolve("plugins.work").toAbsolutePath());
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
		Path jar = grosJarSigne(dossier.resolve("gros"));

		refusSansTrace(manager.installer(jar), "trop volumineux");
	}

	@Test
	void demarrage_ecarte_un_jar_trop_volumineux_sur_les_octets_lus() throws Exception {
		proprietes.setTailleMaxJarMo(1);
		grosJarSigne(repertoirePlugins);

		manager.demarrer();

		assertThat(manager.actifs()).isEmpty();
		assertThat(copiesDeTravail()).isEmpty();
		assertThat(capture.list).anyMatch(e -> e.getLevel() == Level.WARN
				&& e.getFormattedMessage().contains("testnotes-1.0.0.jar")
				&& e.getFormattedMessage().contains("trop volumineux"));
	}

	/** Jar signé d'environ 2 Mo (entrée stockée sans compression) : {@code <dossier>/testnotes-1.0.0.jar}. */
	private static Path grosJarSigne(Path dossierJar) throws IOException {
		Path jar = dossierJar.resolve("testnotes-1.0.0.jar");
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
		return JarDePluginDeTest.signer(jar);
	}

	/**
	 * Crée un lien symbolique, ou saute le test (pas d'erreur) si le système ne le permet pas : sous Windows, il faut le
	 * privilège de création de liens (mode développeur ou administrateur).
	 */
	private static void lienSymbolique(Path lien, Path cible) throws IOException {
		try {
			Files.createSymbolicLink(lien, cible);
		}
		catch (FileAlreadyExistsException e) {
			throw e;
		}
		catch (UnsupportedOperationException | FileSystemException e) {
			Assumptions.assumeTrue(false, "liens symboliques indisponibles ici : " + e.getMessage());
		}
	}

	@Test
	void sig_lien_symbolique_est_refusee() throws Exception {
		Path jar = JarDePluginDeTest.signer(jarNotes("v1", "1.0.0"));
		Path vraie = dossier.resolve("ailleurs.sig");
		Files.move(JarDePluginDeTest.sig(jar), vraie);
		lienSymbolique(JarDePluginDeTest.sig(jar), vraie); // signature valide, mais à travers un lien

		refusSansTrace(manager.installer(jar), "pas un fichier ordinaire");
	}

	@Test
	void dossier_de_travail_hors_du_depot_et_reserve_au_proprietaire() throws Exception {
		manager.installer(JarDePluginDeTest.signer(jarNotes("v1", "1.0.0")));

		Path travail = manager.repertoireTravail();
		assertThat(travail.startsWith(repertoirePlugins.toAbsolutePath())).isFalse();
		if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
			assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(travail, LinkOption.NOFOLLOW_LINKS)))
				.isEqualTo("rwx------");
		}
		else {
			List<AclEntry> acl = Files
				.getFileAttributeView(travail, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
				.getAcl();
			assertThat(acl).hasSize(1);
			assertThat(acl.get(0).principal()).isEqualTo(Files.getOwner(travail, LinkOption.NOFOLLOW_LINKS));
		}
	}

	@Test
	void dossier_de_travail_lien_symbolique_est_refuse() throws Exception {
		Path cible = Files.createDirectories(dossier.resolve("cible-du-lien"));
		Path temoin = Files.writeString(cible.resolve("a-garder.jar"), "pas à moi");
		lienSymbolique(manager.repertoireTravail(), cible);
		Path jar = JarDePluginDeTest.signer(jarNotes("v1", "1.0.0"));
		try {
			ResultatInstallation r = manager.installer(jar);

			assertThat(r.statut()).isEqualTo(Statut.ECHEC);
			assertThat(r.raisons()).anyMatch(x -> x.contains("dossier de travail refusé"));
			assertThat(manager.actifs()).isEmpty();
			assertThat(schemaExiste()).isFalse();
			// Ni copie écrite à travers le lien, ni purge à travers lui au démarrage.
			Path depose = repertoirePlugins.resolve("testnotes-1.0.0.jar");
			Files.createDirectories(repertoirePlugins);
			Files.copy(jar, depose);
			Files.copy(JarDePluginDeTest.sig(jar), JarDePluginDeTest.sig(depose));
			manager.demarrer();
			assertThat(manager.actifs()).isEmpty();
			try (Stream<Path> s = Files.list(cible)) {
				assertThat(s.toList()).containsExactly(temoin);
			}
		}
		finally {
			Files.delete(manager.repertoireTravail()); // le lien seul, avant le nettoyage de apres()
		}
	}

	@Test
	void dossier_de_travail_d_un_autre_utilisateur_est_refuse() throws Exception {
		Files.createDirectories(manager.repertoireTravail());
		manager.proprietaireAttendu(() -> "un-autre-utilisateur");

		ResultatInstallation r = manager.installer(JarDePluginDeTest.signer(jarNotes("v1", "1.0.0")));

		assertThat(r.statut()).isEqualTo(Statut.ECHEC);
		assertThat(r.raisons()).anyMatch(x -> x.contains("appartient pas"));
		assertThat(manager.actifs()).isEmpty();
		assertThat(schemaExiste()).isFalse();
		try (Stream<Path> s = Files.list(manager.repertoireTravail())) {
			assertThat(s.toList()).isEmpty();
		}
	}

	@Test
	void retour_arriere_reverifie_la_copie_conservee_avant_de_la_charger() throws Exception {
		assertThat(manager.installer(JarDePluginDeTest.signer(jarNotes("v1", "1.0.0"))).statut()).isEqualTo(Statut.OK);
		Path ancienneCopie = manager.actifs().get("testnotes").jarCopie;
		// Jar 1.0.0 chargeable mais NON signé (une migration en plus) : substitué à la copie conservée pendant la
		// mise à jour, après la fermeture de l'ancienne génération et avant le retour arrière.
		Path substitut = JarDePluginDeTest.pluginNotes(dossier.resolve("substitut"), "1.0.0",
				Map.of("V1__note.sql", JarDePluginDeTest.MIGRATION_NOTES_V1, "V2__intrus.sql",
						"CREATE TABLE intrus (id int)"));
		demarreur.versionPiegee = "1.1.0";
		demarreur.piege = () -> {
			try {
				Files.copy(substitut, ancienneCopie, StandardCopyOption.REPLACE_EXISTING);
			}
			catch (IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		};

		ResultatInstallation r = manager.installer(JarDePluginDeTest.signer(jarNotes("v2", "1.1.0")));

		assertThat(r.statut()).as("%s", r.raisons()).isEqualTo(Statut.ECHEC);
		assertThat(r.raisons())
			.anyMatch(x -> x.startsWith("Retour arrière impossible") && x.contains("signature invalide"));
		assertThat(manager.actifs()).isEmpty();
		assertThat(copiesDeTravail()).isEmpty();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables "
				+ "WHERE table_schema = 'plugin_testnotes' AND table_name = 'intrus'", Integer.class)).isZero();
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

	/** Dépose {@code testnotes-<version>.jar} (migration V1) dans le répertoire des plugins. */
	private Path deposer(String version) {
		return JarDePluginDeTest.pluginNotes(repertoirePlugins, version,
				Map.of("V1__note.sql", JarDePluginDeTest.MIGRATION_NOTES_V1));
	}

	@Test
	void un_jar_plus_haut_mais_mal_signe_ne_gagne_pas_au_demarrage() throws Exception {
		KeyPair attaquant = JarDePluginDeTest.nouvellePaire();
		JarDePluginDeTest.signer(deposer("2.0.0"), attaquant.getPrivate(), JarDePluginDeTest.KEY_ID_TEST);
		JarDePluginDeTest.signer(deposer("1.5.0"));

		manager.demarrer();

		assertThat(manager.actifs()).containsOnlyKeys("testnotes");
		assertThat(manager.actifs().get("testnotes").version).isEqualTo("1.5.0");
		assertThat(capture.list).anyMatch(e -> e.getLevel() == Level.WARN
				&& e.getFormattedMessage().contains("testnotes-2.0.0.jar")
				&& e.getFormattedMessage().contains("signature invalide"));
	}

	@Test
	void jar_plus_haut_signe_gagne() throws Exception {
		JarDePluginDeTest.signer(deposer("1.5.0"));
		JarDePluginDeTest.signer(deposer("2.0.0"));

		manager.demarrer();

		assertThat(manager.actifs()).containsOnlyKeys("testnotes");
		assertThat(manager.actifs().get("testnotes").version).isEqualTo("2.0.0");
		// Démarrage direct de la plus haute : jamais de 1.5.0 chargée puis mise à jour.
		assertThat(capture.list).noneMatch(e -> e.getFormattedMessage().contains("mis à jour"));
	}

	@Test
	void version_choisie_au_demarrage_lue_dans_la_copie_verifiee_pas_dans_le_jar_depose() throws Exception {
		Path a = JarDePluginDeTest.signer(deposer("1.0.0"));
		JarDePluginDeTest.signer(deposer("1.5.0"));
		Path substitut = jarNotes("substitut", "9.0.0"); // non signé, version plus haute
		// Juste après la vérification de la copie de a, le jar déposé est remplacé : le choix de version ne doit pas
		// relire le fichier déposé (il y trouverait 9.0.0, gagnerait, puis serait refusé à l'installation).
		manager.apresVerificationAuDemarrage(jar -> {
			if (jar.getFileName().equals(a.getFileName())) {
				try {
					Files.copy(substitut, jar, StandardCopyOption.REPLACE_EXISTING);
				}
				catch (IOException e) {
					throw new java.io.UncheckedIOException(e);
				}
			}
		});

		manager.demarrer();

		assertThat(manager.actifs()).containsOnlyKeys("testnotes");
		assertThat(manager.actifs().get("testnotes").version).isEqualTo("1.5.0");
	}

	@Test
	void dossier_de_travail_egal_au_repertoire_des_plugins_est_refuse_sans_rien_supprimer() throws Exception {
		Path jar = JarDePluginDeTest.signer(deposer("1.0.0"));
		proprietes.setRepertoireTravail(repertoirePlugins.toString());
		try {
			manager.demarrer(); // la purge du dossier de travail effacerait les jars déposés

			assertThat(jar).exists();
			assertThat(JarDePluginDeTest.sig(jar)).exists();
			assertThat(manager.actifs()).isEmpty();

			ResultatInstallation r = manager.installer(jar);

			assertThat(r.statut()).isEqualTo(Statut.ECHEC);
			assertThat(r.raisons()).anyMatch(x -> x.contains("dossier de travail refusé"));
			assertThat(manager.actifs()).isEmpty();
			assertThat(schemaExiste()).isFalse();
			assertThat(jar).exists();
		}
		finally {
			proprietes.setRepertoireTravail("");
		}
	}

	@Test
	void dossier_de_travail_contenant_le_repertoire_des_plugins_est_refuse() throws Exception {
		Path jar = JarDePluginDeTest.signer(deposer("1.0.0"));
		// Parent écrit avec « .. » : la comparaison se fait sur des chemins normalisés.
		proprietes.setRepertoireTravail(repertoirePlugins.resolve("sous").resolve("..").resolve("..").toString());
		try {
			ResultatInstallation r = manager.installer(jar);

			assertThat(r.statut()).isEqualTo(Statut.ECHEC);
			assertThat(r.raisons()).anyMatch(x -> x.contains("dossier de travail refusé"));
			assertThat(manager.actifs()).isEmpty();
			assertThat(schemaExiste()).isFalse();
		}
		finally {
			proprietes.setRepertoireTravail("");
		}
	}

	@Test
	void signature_obligatoire_n_avertit_pas_de_jars_non_signes_au_demarrage() {
		assertThat(capture.list).noneMatch(e -> e.getFormattedMessage().contains("signature-obligatoire=false"));
	}

}
