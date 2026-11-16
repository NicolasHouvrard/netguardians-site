package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.logicielpourtous.site.sdk.SignatureJar;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Dépôt du Maître : publication vérifiée, catalogue, ouverture sans traversée de chemin. */
class DepotPluginsTest {

	@TempDir
	Path racine;

	private PlateformeProperties proprietes;

	private DepotPlugins depot;

	private Path dossierDepot;

	private int compteur;

	@BeforeEach
	void preparer() {
		dossierDepot = racine.resolve("depot");
		proprietes = new PlateformeProperties();
		proprietes.setDepotMaitre(dossierDepot.toString());
		proprietes.setRepertoirePlugins(racine.resolve("plugins").toString());
		depot = new DepotPlugins(proprietes, new ClesPubliques(JarDePluginDeTest.clesDeTest()));
	}

	/** Jar minimal {@code <nom>-<version>.jar} construit hors du dépôt (non signé). */
	private Path jar(String nom, String version, Map<String, String> enPlus) {
		Map<String, String> props = new LinkedHashMap<>(Map.of("coeur-min", "1.0.0", "coeur-max", "2.0.0",
				"mise-a-jour", "chaud", "paquet", nom, "configuration", nom + ".Config"));
		props.putAll(enPlus);
		return JarDePluginDeTest.creer(racine.resolve("construction-" + (compteur++)), nom, version, props, Map.of());
	}

	private Path jarSigne(String nom, String version) {
		return JarDePluginDeTest.signer(jar(nom, version, Map.of()));
	}

	private EntreeCatalogue publier(Path jar) throws IOException {
		try (InputStream j = Files.newInputStream(jar); InputStream s = Files.newInputStream(JarDePluginDeTest.sig(jar))) {
			return depot.publier(j, s);
		}
	}

	private static InputStream texte(String s) {
		return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
	}

	private static String sha256(Path fichier) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(fichier)));
	}

	/** Fichiers ordinaires du dépôt, sous-dossiers compris (temporaires inclus). */
	private List<Path> fichiersDuDepot() throws IOException {
		if (!Files.exists(dossierDepot)) {
			return List.of();
		}
		try (Stream<Path> s = Files.walk(dossierDepot)) {
			return s.filter(Files::isRegularFile).toList();
		}
	}

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
	void publication_valide_apparait_au_catalogue() throws Exception {
		Path jar = jarSigne("alpha", "1.2.0");

		EntreeCatalogue entree = publier(jar);

		assertThat(entree).isEqualTo(new EntreeCatalogue("alpha", "1.2.0", "chaud", "1.0.0", "2.0.0", sha256(jar),
				Files.size(jar)));
		assertThat(depot.catalogue()).containsExactly(entree);
		Path stocke = depot.ouvrir("alpha", "1.2.0", "jar");
		assertThat(stocke.getFileName().toString()).isEqualTo("alpha-1.2.0.jar");
		assertThat(Files.readAllBytes(stocke)).isEqualTo(Files.readAllBytes(jar));
		Path sig = depot.ouvrir("alpha", "1.2.0", "sig");
		assertThat(sig.getFileName().toString()).isEqualTo("alpha-1.2.0.jar.sig");
		assertThat(Files.readString(sig)).isEqualTo(Files.readString(JarDePluginDeTest.sig(jar)));
		// Rien ne traîne dans la zone temporaire.
		assertThat(fichiersDuDepot()).containsExactlyInAnyOrder(stocke, sig);
	}

	@Test
	void jar_mal_signe_refuse_et_rien_ecrit() throws Exception {
		// 1. Jar signé puis modifié d'un octet.
		Path modifie = jarSigne("alpha", "1.0.0");
		byte[] octets = Files.readAllBytes(modifie);
		octets[octets.length - 1] ^= 1;
		Files.write(modifie, octets);
		// 2. Signé par une clé d'attaquant sous le keyId de confiance.
		KeyPair attaquant = JarDePluginDeTest.nouvellePaire();
		Path usurpe = JarDePluginDeTest.signer(jar("alpha", "1.0.0", Map.of()), attaquant.getPrivate(),
				JarDePluginDeTest.KEY_ID_TEST);
		// 3. Signature valide d'un AUTRE jar.
		Path autre = jarSigne("alpha", "1.0.1");
		Path sansSaSig = jar("alpha", "1.0.0", Map.of());

		for (Path jar : List.of(modifie, usurpe)) {
			assertThatThrownBy(() -> publier(jar)).isInstanceOf(DepotPlugins.DepotRefuse.class)
				.hasMessageContaining("signature invalide");
		}
		assertThatThrownBy(() -> depot.publier(Files.newInputStream(sansSaSig),
				Files.newInputStream(JarDePluginDeTest.sig(autre))))
			.isInstanceOf(DepotPlugins.DepotRefuse.class)
			.hasMessageContaining("signature invalide");
		// 4. .sig malformées : jamais d'exception non contrôlée.
		for (String sig : List.of("", "{", "pas du json", "{\"keyId\":\"test-1\",\"algo\":\"RSA\",\"signature\":\"AA==\"}",
				"{\"keyId\":\"test-1\",\"algo\":\"Ed25519\",\"signature\":\"!!!\"}", "{\"keyId\":\"test-1\"}")) {
			assertThatThrownBy(() -> depot.publier(Files.newInputStream(sansSaSig), texte(sig)))
				.isInstanceOf(DepotPlugins.DepotRefuse.class)
				.satisfies(e -> assertThat(((DepotPlugins.DepotRefuse) e).motif()).isEqualTo(DepotPlugins.Motif.INVALIDE));
		}

		assertThat(fichiersDuDepot()).isEmpty();
		assertThat(depot.catalogue()).isEmpty();
	}

	@Test
	void meme_version_contenu_different_refusee() throws Exception {
		Path premier = jarSigne("alpha", "1.0.0");
		EntreeCatalogue publie = publier(premier);
		Path autreContenu = JarDePluginDeTest.signer(jar("alpha", "1.0.0", Map.of("dependances", "beta")));

		assertThatThrownBy(() -> publier(autreContenu)).isInstanceOf(DepotPlugins.DepotRefuse.class)
			.hasMessageContaining("contenu différent")
			.satisfies(e -> assertThat(((DepotPlugins.DepotRefuse) e).motif()).isEqualTo(DepotPlugins.Motif.CONFLIT));

		assertThat(depot.catalogue()).containsExactly(publie);
		assertThat(Files.readAllBytes(depot.ouvrir("alpha", "1.0.0", "jar"))).isEqualTo(Files.readAllBytes(premier));
		assertThat(fichiersDuDepot()).hasSize(2);
	}

	@Test
	void meme_version_meme_contenu_idempotente() throws Exception {
		Path jar = jarSigne("alpha", "1.0.0");
		EntreeCatalogue premiere = publier(jar);
		EntreeCatalogue seconde = publier(jar);

		assertThat(seconde).isEqualTo(premiere);
		assertThat(depot.catalogue()).containsExactly(premiere);
		assertThat(fichiersDuDepot()).hasSize(2);
	}

	@Test
	void nom_ou_version_avec_chemin_refuse() throws Exception {
		publier(jarSigne("alpha", "1.0.0"));
		for (String nom : List.of("../x", "a/b", "a\\b", "..", "", "Alpha", "alpha\0")) {
			assertThatThrownBy(() -> depot.ouvrir(nom, "1.0.0", "jar")).isInstanceOf(IllegalArgumentException.class);
		}
		for (String version : List.of("1.0.0/../..", "1.0.0\\..\\x", "..", "1.0", "01.0.0", "1.0.0+build", "1.0.0 ",
				"1.0.0-", "")) {
			assertThatThrownBy(() -> depot.ouvrir("alpha", version, "jar")).isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(() -> depot.ouvrir(null, "1.0.0", "jar")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> depot.ouvrir("alpha", null, "jar")).isInstanceOf(IllegalArgumentException.class);
		for (String extension : List.of("exe", "jar/../x", "", "properties")) {
			assertThatThrownBy(() -> depot.ouvrir("alpha", "1.0.0", extension)).isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(() -> depot.ouvrir("alpha", "9.9.9", "jar")).isInstanceOf(NoSuchFileException.class);

		// Une version « chemin » dans un manifeste signé est refusée à la publication : rien n'est écrit.
		// (la clé « version » donnée en plus remplace celle écrite par l'aide : la dernière lue l'emporte)
		Path chemin = JarDePluginDeTest.signer(jar("alpha", "1.0.0", Map.of("version", "1.0.0/../..")));
		assertThatThrownBy(() -> publier(chemin)).isInstanceOf(DepotPlugins.DepotRefuse.class)
			.hasMessageContaining("version");
		assertThat(fichiersDuDepot()).hasSize(2);
	}

	@Test
	void catalogue_trie_et_sha256_correct() throws Exception {
		Path b2 = jarSigne("beta", "2.0.0");
		Path a10 = jarSigne("alpha", "1.10.0");
		Path a9 = jarSigne("alpha", "1.9.0");
		Path a10rc = jarSigne("alpha", "1.10.0-rc1");
		for (Path p : List.of(b2, a10, a9, a10rc)) {
			publier(p);
		}

		List<EntreeCatalogue> catalogue = depot.catalogue();

		assertThat(catalogue).extracting(e -> e.nom() + ":" + e.version())
			.containsExactly("alpha:1.9.0", "alpha:1.10.0-rc1", "alpha:1.10.0", "beta:2.0.0");
		Map<String, Path> parVersion = Map.of("1.9.0", a9, "1.10.0-rc1", a10rc, "1.10.0", a10, "2.0.0", b2);
		for (EntreeCatalogue e : catalogue) {
			Path source = parVersion.get(e.version());
			assertThat(e.sha256()).isEqualTo(sha256(source)).hasSize(64);
			assertThat(e.taille()).isEqualTo(Files.size(source));
		}
	}

	// ---- Compléments ----

	@Test
	void jar_trop_volumineux_refuse_pendant_la_copie_avant_verification() throws Exception {
		proprietes.setTailleMaxJarMo(1);
		// Flux sans fin : sans borne pendant la copie, le test ne se terminerait pas.
		InputStream sansFin = new InputStream() {
			@Override
			public int read() {
				return 0;
			}

			@Override
			public int read(byte[] b, int off, int len) {
				return len;
			}
		};
		DepotPlugins.DepotRefuse refus = assertTimeoutPreemptively(Duration.ofSeconds(20),
				() -> org.junit.jupiter.api.Assertions.assertThrows(DepotPlugins.DepotRefuse.class,
						() -> depot.publier(sansFin, texte("{}"))));
		assertThat(refus.motif()).isEqualTo(DepotPlugins.Motif.TROP_VOLUMINEUX);
		assertThat(refus.getMessage()).contains("1 Mo");
		assertThat(fichiersDuDepot()).isEmpty();
	}

	@Test
	void signature_trop_volumineuse_refusee() throws Exception {
		Path jar = jarSigne("alpha", "1.0.0");
		String enorme = "{\"keyId\":\"" + "x".repeat(20_000) + "\"}";
		assertThatThrownBy(() -> depot.publier(Files.newInputStream(jar), texte(enorme)))
			.isInstanceOf(DepotPlugins.DepotRefuse.class)
			.hasMessageContaining("signature");
		assertThat(fichiersDuDepot()).isEmpty();
	}

	@Test
	void raison_de_refus_n_echo_pas_le_texte_brut_de_l_attaquant() throws Exception {
		Path jar = jar("alpha", "1.0.0", Map.of());
		String sig = "{\"keyId\":\"<script>‮\u0007" + "z".repeat(500) + "\",\"algo\":\"Ed25519\",\"signature\":\"AA==\"}";
		assertThatThrownBy(() -> depot.publier(Files.newInputStream(jar), texte(sig)))
			.isInstanceOf(DepotPlugins.DepotRefuse.class)
			.satisfies(e -> {
				String message = e.getMessage();
				assertThat(message).doesNotContain("‮").doesNotContain("\u0007").doesNotContain("z".repeat(100));
				assertThat(message.length()).isLessThan(300);
			});
	}

	@Test
	void manifeste_invalide_dans_un_jar_signe_refuse_sans_rien_ecrire() throws Exception {
		Path jar = JarDePluginDeTest.signer(jar("alpha", "1.0.0", Map.of("mise-a-jour", "‮inconnu")));
		assertThatThrownBy(() -> publier(jar)).isInstanceOf(DepotPlugins.DepotRefuse.class)
			.hasMessageContaining("manifeste")
			.satisfies(e -> assertThat(e.getMessage()).doesNotContain("‮"));
		assertThat(fichiersDuDepot()).isEmpty();
	}

	@Test
	void le_catalogue_ne_montre_que_les_paires_completes_et_verifiees() throws Exception {
		publier(jarSigne("alpha", "1.0.0"));
		// États laissés par un arrêt brutal ou un dépôt manuel : jamais présentés.
		Path sansSig = jar("beta", "1.0.0", Map.of());
		Files.copy(sansSig, dossierDepot.resolve("beta-1.0.0.jar"));
		Path sigOrpheline = jarSigne("gamma", "1.0.0");
		Files.copy(JarDePluginDeTest.sig(sigOrpheline), dossierDepot.resolve("gamma-1.0.0.jar.sig"));
		Path malSigne = jar("delta", "1.0.0", Map.of());
		Files.copy(malSigne, dossierDepot.resolve("delta-1.0.0.jar"));
		Files.writeString(dossierDepot.resolve("delta-1.0.0.jar.sig"), "{}");
		Path mauvaisNom = jarSigne("epsilon", "1.0.0");
		Files.copy(mauvaisNom, dossierDepot.resolve("epsilon-9.0.0.jar"));
		Files.copy(JarDePluginDeTest.sig(mauvaisNom), dossierDepot.resolve("epsilon-9.0.0.jar.sig"));

		assertThat(depot.catalogue()).extracting(EntreeCatalogue::nom).containsExactly("alpha");
		assertThatThrownBy(() -> depot.ouvrir("beta", "1.0.0", "jar")).isInstanceOf(NoSuchFileException.class);
		assertThatThrownBy(() -> depot.ouvrir("gamma", "1.0.0", "sig")).isInstanceOf(NoSuchFileException.class);

		// La sig orpheline d'un arrêt brutal n'empêche pas la publication de la paire complète.
		publier(sigOrpheline);
		assertThat(depot.catalogue()).extracting(EntreeCatalogue::nom).containsExactly("alpha", "gamma");
	}

	@Test
	void ouvrir_et_catalogue_refusent_un_lien_symbolique() throws Exception {
		publier(jarSigne("alpha", "1.0.0"));
		Path cible = jarSigne("beta", "1.0.0");
		lienSymbolique(dossierDepot.resolve("beta-1.0.0.jar"), cible);
		lienSymbolique(dossierDepot.resolve("beta-1.0.0.jar.sig"), JarDePluginDeTest.sig(cible));

		assertThatThrownBy(() -> depot.ouvrir("beta", "1.0.0", "jar")).isInstanceOf(NoSuchFileException.class);
		assertThatThrownBy(() -> depot.ouvrir("beta", "1.0.0", "sig")).isInstanceOf(NoSuchFileException.class);
		assertThat(depot.catalogue()).extracting(EntreeCatalogue::nom).containsExactly("alpha");
	}

	@Test
	void depot_lien_symbolique_refuse_et_rien_ecrit() throws Exception {
		Path ailleurs = Files.createDirectories(racine.resolve("ailleurs"));
		lienSymbolique(dossierDepot, ailleurs);

		assertThatThrownBy(() -> publier(jarSigne("alpha", "1.0.0"))).isInstanceOf(IOException.class)
			.hasMessageContaining("dépôt refusé");
		try (Stream<Path> s = Files.walk(ailleurs)) {
			assertThat(s.filter(Files::isRegularFile).toList()).isEmpty();
		}
		assertThat(depot.catalogue()).isEmpty();
	}

	@Test
	void depot_d_un_autre_utilisateur_refuse() throws Exception {
		Files.createDirectories(dossierDepot);
		depot.proprietaireAttendu(() -> "quelqu-un-d-autre");

		assertThatThrownBy(() -> publier(jarSigne("alpha", "1.0.0"))).isInstanceOf(IOException.class)
			.hasMessageContaining("dépôt refusé");
		assertThat(fichiersDuDepot()).isEmpty();
	}

	@Test
	void depot_confondu_avec_le_repertoire_des_plugins_refuse() throws Exception {
		proprietes.setRepertoirePlugins(dossierDepot.toString());
		assertThatThrownBy(() -> publier(jarSigne("alpha", "1.0.0"))).isInstanceOf(IOException.class)
			.hasMessageContaining("dépôt refusé");
		proprietes.setRepertoirePlugins(dossierDepot.resolve("plugins").toString());
		assertThatThrownBy(() -> publier(jarSigne("alpha", "1.0.0"))).isInstanceOf(IOException.class)
			.hasMessageContaining("dépôt refusé");
		assertThat(fichiersDuDepot()).isEmpty();
	}

	@Test
	void depot_reserve_au_proprietaire() throws Exception {
		publier(jarSigne("alpha", "1.0.0"));
		if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
			assertThat(java.nio.file.attribute.PosixFilePermissions
				.toString(Files.getPosixFilePermissions(dossierDepot, LinkOption.NOFOLLOW_LINKS))).isEqualTo("rwx------");
		}
		else {
			var vue = Files.getFileAttributeView(dossierDepot, java.nio.file.attribute.AclFileAttributeView.class,
					LinkOption.NOFOLLOW_LINKS);
			assertThat(vue.getAcl()).hasSize(1);
			assertThat(vue.getAcl().get(0).principal()).isEqualTo(Files.getOwner(dossierDepot, LinkOption.NOFOLLOW_LINKS));
		}
	}

	// ---- Correctifs de revue (tour 1) ----

	/** M1 : pré-version en minuscules seulement (un système de fichiers insensible à la casse confondrait RC1 et rc1). */
	@Test
	void pre_version_en_majuscules_refusee() throws Exception {
		assertThatThrownBy(() -> depot.ouvrir("alpha", "1.0.0-RC1", "jar")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> depot.ouvrir("alpha", "1.0.0-rc.A", "jar")).isInstanceOf(IllegalArgumentException.class);
		Path majuscules = jarSigne("alpha", "1.0.0-RC1");
		assertThatThrownBy(() -> publier(majuscules)).isInstanceOf(DepotPlugins.DepotRefuse.class)
			.hasMessageContaining("version");
		assertThat(publier(jarSigne("alpha", "1.0.0-rc-1.2")).version()).isEqualTo("1.0.0-rc-1.2");
	}

	/** M2 : dépôt confondu avec le dossier de travail du cœur (dans les deux sens, défaut compris). */
	@Test
	void depot_confondu_avec_le_dossier_de_travail_refuse() throws Exception {
		Path travail = racine.resolve("travail");
		List<Runnable> configurations = List.of(
				() -> proprietes.setRepertoireTravail(dossierDepot.toString()),
				() -> proprietes.setRepertoireTravail(dossierDepot.resolve("sous").toString()),
				() -> {
					proprietes.setRepertoireTravail(travail.toString());
					proprietes.setDepotMaitre(travail.resolve("depot").toString());
				},
				() -> {
					proprietes.setRepertoireTravail("");
					proprietes.setDepotMaitre(racine.resolve("plugins.work").toString());
				});
		for (Runnable configurer : configurations) {
			proprietes.setDepotMaitre(dossierDepot.toString());
			configurer.run();
			assertThatThrownBy(() -> publier(jarSigne("alpha", "1.0.0"))).isInstanceOf(IOException.class)
				.hasMessageContaining("dépôt refusé");
		}
		assertThat(fichiersDuDepot()).isEmpty();
		assertThat(Files.exists(racine.resolve("plugins.work"))).isFalse();
	}

	/** M3 : une SecurityException à la lecture du manifeste (jar signé JAR cassé) n'est ni un 500 ni une panne du catalogue. */
	@Test
	void securityexception_a_la_lecture_du_manifeste_est_controlee() throws Exception {
		publier(jarSigne("alpha", "1.0.0"));
		publier(jarSigne("beta", "1.0.0"));
		DepotPlugins neuf = new DepotPlugins(proprietes, new ClesPubliques(JarDePluginDeTest.clesDeTest()));
		neuf.lecteurManifeste(jar -> {
			if (jar.getFileName().toString().startsWith("beta") || jar.getFileName().toString().startsWith("publication")) {
				throw new SecurityException("SHA-256 digest error for x.class");
			}
			return LecteurManifeste.lire(jar);
		});

		assertThat(neuf.catalogue()).extracting(EntreeCatalogue::nom).containsExactly("alpha");
		Path gamma = jarSigne("gamma", "1.0.0");
		assertThatThrownBy(() -> neuf.publier(Files.newInputStream(gamma),
				Files.newInputStream(JarDePluginDeTest.sig(gamma))))
			.isInstanceOf(DepotPlugins.DepotRefuse.class)
			.hasMessageContaining("manifeste")
			.satisfies(e -> assertThat(((DepotPlugins.DepotRefuse) e).motif()).isEqualTo(DepotPlugins.Motif.INVALIDE));
	}

	/** M10 : un refus de la .sig (trop volumineuse, UTF-8 invalide) est journalisé comme les autres refus. */
	@Test
	void refus_de_signature_journalise() throws Exception {
		ch.qos.logback.classic.Logger journal = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
			.getLogger(DepotPlugins.class);
		ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> capture = new ch.qos.logback.core.read.ListAppender<>();
		capture.start();
		journal.addAppender(capture);
		try {
			Path jar = jarSigne("alpha", "1.0.0");
			assertThatThrownBy(() -> depot.publier(Files.newInputStream(jar), texte("x".repeat(20_000))))
				.isInstanceOf(DepotPlugins.DepotRefuse.class);
			assertThatThrownBy(() -> depot.publier(Files.newInputStream(jar),
					new ByteArrayInputStream(new byte[] { (byte) 0xff, (byte) 0xfe })))
				.isInstanceOf(DepotPlugins.DepotRefuse.class);
		}
		finally {
			journal.detachAppender(capture);
		}
		assertThat(capture.list).filteredOn(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN
				&& e.getFormattedMessage().contains("publication refusée"))
			.hasSize(2);
		assertThat(fichiersDuDepot()).isEmpty();
	}

	@Test
	void nom_et_version_ne_viennent_que_du_manifeste_verifie() throws Exception {
		// Le jar est construit sous un nom de fichier trompeur : seul le manifeste vérifié décide du nom stocké.
		Path jar = jarSigne("alpha", "1.0.0");
		Path trompeur = Files.copy(jar, jar.resolveSibling("..%2f..%2fevil-6.6.6.jar"));
		Files.copy(JarDePluginDeTest.sig(jar), JarDePluginDeTest.sig(trompeur));

		EntreeCatalogue e = publier(trompeur);

		assertThat(e.nom()).isEqualTo("alpha");
		assertThat(e.version()).isEqualTo("1.0.0");
		assertThat(Files.isRegularFile(dossierDepot.resolve("alpha-1.0.0.jar"))).isTrue();
		assertThat(SignatureJar.verifier(dossierDepot.resolve("alpha-1.0.0.jar"),
				Files.readString(dossierDepot.resolve("alpha-1.0.0.jar.sig")), JarDePluginDeTest.clesDeTest()).valide())
			.isTrue();
	}

}
