package com.logicielpourtous.site.features.plateforme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.logicielpourtous.site.features.plateforme.ResultatInstallation.Statut;
import com.logicielpourtous.site.sdk.ManifestePlugin;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tireur de plugins avec une source simulée et un installateur simulé (aucun HTTP, aucun contexte Spring). */
class TireurPluginsTest {

	@TempDir
	Path racine;

	private Path plugins;

	private PlateformeProperties proprietes;

	private SourceSimulee source;

	/** Versions actives simulées (nom vers version), mises à jour par l'installateur simulé sur OK. */
	private final Map<String, String> actives = new ConcurrentHashMap<>();

	/** Jars passés à l'installateur. */
	private final List<Path> installes = new ArrayList<>();

	/** Réponse forcée de l'installateur par nom de plugin (sinon OK avec le manifeste lu). */
	private final Map<String, Function<ManifestePlugin, ResultatInstallation>> reponses = new HashMap<>();

	private TireurPlugins tireur;

	private int compteur;

	record Rapport(String nom, String version, Statut statut, List<String> raisons) {
	}

	/** Source simulée : catalogue modifiable, jars servis depuis des fichiers signés, rapports enregistrés. */
	static final class SourceSimulee implements SourcePlugins {

		final List<EntreeCatalogue> catalogue = new ArrayList<>();

		final Map<String, Path> jars = new HashMap<>();

		final List<String> telechargements = new ArrayList<>();

		final List<Path> dossiers = new ArrayList<>();

		final List<Rapport> rapports = new ArrayList<>();

		Exception panne;

		boolean catalogueNul;

		IOException panneRapport;

		/** Si non nul : chemin renvoyé par telecharger à la place du vrai. */
		Path cheminForce;

		@Override
		public List<EntreeCatalogue> catalogue() throws IOException {
			if (panne instanceof IOException io) {
				throw io;
			}
			if (panne instanceof RuntimeException r) {
				throw r;
			}
			return catalogueNul ? null : new ArrayList<>(catalogue);
		}

		@Override
		public Path telecharger(EntreeCatalogue e, Path dossier) throws IOException {
			telechargements.add(e.nom() + "-" + e.version());
			dossiers.add(dossier);
			Path source = jars.get(e.nom() + "-" + e.version());
			Path jar = dossier.resolve(e.nom() + "-" + e.version() + ".jar");
			Files.copy(source, jar);
			Files.copy(JarDePluginDeTest.sig(source), JarDePluginDeTest.sig(jar));
			return cheminForce != null ? cheminForce : jar;
		}

		@Override
		public void rapporter(String nom, String version, Statut statut, List<String> raisons) throws IOException {
			if (panneRapport != null) {
				throw panneRapport;
			}
			rapports.add(new Rapport(nom, version, statut, List.copyOf(raisons)));
		}

	}

	@BeforeEach
	void preparer() {
		plugins = racine.resolve("plugins");
		proprietes = new PlateformeProperties();
		proprietes.setRepertoirePlugins(plugins.toString());
		proprietes.setVersionCoeur("1.5.0");
		source = new SourceSimulee();
		tireur = nouveauTireur();
	}

	private TireurPlugins nouveauTireur() {
		return new TireurPlugins(source, proprietes, new ClesPubliques(JarDePluginDeTest.clesDeTest()), jar -> {
			installes.add(jar);
			ManifestePlugin m = LecteurManifeste.lire(jar);
			Function<ManifestePlugin, ResultatInstallation> forcee = reponses.get(m.nom());
			ResultatInstallation r = forcee == null ? null : forcee.apply(m);
			if (r != null) {
				return r;
			}
			actives.put(m.nom(), m.version());
			return new ResultatInstallation(Statut.OK, m.nom(), m.version(), List.of(), 0);
		}, () -> Map.copyOf(actives));
	}

	/** Jar signé (clé de test) {@code <nom>-<version>.jar}, construit hors du répertoire des plugins. */
	private Path jarSigne(String nom, String version, Map<String, String> enPlus) {
		Map<String, String> props = new LinkedHashMap<>(Map.of("coeur-min", "1.0.0", "coeur-max", "2.0.0",
				"mise-a-jour", "chaud", "paquet", nom, "configuration", nom + ".Config"));
		props.putAll(enPlus);
		return JarDePluginDeTest
			.signer(JarDePluginDeTest.creer(racine.resolve("construction-" + (compteur++)), nom, version, props, Map.of()));
	}

	/** Publie un jar signé dans la source simulée (catalogue + octets) et renvoie l'entrée. */
	private EntreeCatalogue publier(String nom, String version) throws IOException {
		return publier(nom, version, jarSigne(nom, version, Map.of()), "1.0.0", "2.0.0");
	}

	private EntreeCatalogue publier(String nom, String version, Path jar, String coeurMin, String coeurMax)
			throws IOException {
		EntreeCatalogue e = new EntreeCatalogue(nom, version, "chaud", coeurMin, coeurMax, sha256(jar), Files.size(jar));
		source.catalogue.add(e);
		source.jars.put(nom + "-" + version, jar);
		return e;
	}

	private static String sha256(Path fichier) throws IOException {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(fichier)));
		}
		catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private TreeSet<String> fichiers(Path dossier) throws IOException {
		if (!Files.isDirectory(dossier)) {
			return new TreeSet<>();
		}
		try (Stream<Path> s = Files.list(dossier)) {
			return s.map(p -> p.getFileName().toString()).collect(Collectors.toCollection(TreeSet::new));
		}
	}

	/** Fichiers ordinaires du répertoire des plugins (sans le dossier de tirage). */
	private TreeSet<String> deposes() throws IOException {
		TreeSet<String> noms = new TreeSet<>();
		if (!Files.isDirectory(plugins)) {
			return noms;
		}
		try (Stream<Path> s = Files.list(plugins)) {
			s.filter(Files::isRegularFile).forEach(p -> noms.add(p.getFileName().toString()));
		}
		return noms;
	}

	private void deposerAlaMain(Path jar) throws IOException {
		Files.createDirectories(plugins);
		Files.copy(jar, plugins.resolve(jar.getFileName()));
		Files.copy(JarDePluginDeTest.sig(jar), plugins.resolve(jar.getFileName() + ".sig"));
	}

	private static ResultatInstallation resultat(Statut statut, ManifestePlugin m, String... raisons) {
		return new ResultatInstallation(statut, m.nom(), m.version(), List.of(raisons), 0);
	}

	// ---------------------------------------------------------------------------------------------------------------

	@Test
	void nouvelle_version_signee_est_installee_et_rapportee_OK() throws IOException {
		actives.put("alpha", "1.0.0");
		publier("alpha", "1.0.0");
		publier("alpha", "1.1.0");

		tireur.tirer();

		assertThat(source.telechargements).containsExactly("alpha-1.1.0");
		// Téléchargement dans un dossier privé DU répertoire des plugins (même volume), jamais dans le répertoire lui-même.
		Path dossier = source.dossiers.get(0).toAbsolutePath().normalize();
		assertThat(dossier.getParent()).isEqualTo(plugins.toAbsolutePath().normalize());
		assertThat(dossier).isNotEqualTo(plugins.toAbsolutePath().normalize());
		assertThat(installes).containsExactly(plugins.resolve("alpha-1.1.0.jar").toAbsolutePath().normalize());
		assertThat(deposes()).containsExactly("alpha-1.1.0.jar", "alpha-1.1.0.jar.sig");
		assertThat(fichiers(dossier)).as("dossier de tirage vidé").isEmpty();
		assertThat(source.rapports).containsExactly(new Rapport("alpha", "1.1.0", Statut.OK, List.of()));
		assertThat(actives).containsEntry("alpha", "1.1.0");

		// Tirage suivant : déjà à jour, rien n'est retéléchargé ni rapporté.
		tireur.tirer();
		assertThat(source.telechargements).hasSize(1);
		assertThat(source.rapports).hasSize(1);
	}

	@Test
	void nouveau_plugin_absent_localement_est_installe() throws IOException {
		publier("beta", "1.0.0");

		tireur.tirer();

		assertThat(source.rapports).containsExactly(new Rapport("beta", "1.0.0", Statut.OK, List.of()));
		assertThat(deposes()).containsExactly("beta-1.0.0.jar", "beta-1.0.0.jar.sig");
	}

	@Test
	void sha256_different_rejette_et_ne_depose_rien() throws IOException {
		actives.put("alpha", "1.0.0");
		EntreeCatalogue vraie = publier("alpha", "1.1.0");
		source.catalogue.set(0, new EntreeCatalogue("alpha", "1.1.0", "chaud", "1.0.0", "2.0.0", "0".repeat(64),
				vraie.taille()));

		tireur.tirer();

		assertThat(installes).isEmpty();
		assertThat(deposes()).isEmpty();
		assertThat(fichiers(source.dossiers.get(0))).isEmpty();
		assertThat(source.rapports).singleElement().satisfies(r -> {
			assertThat(r.statut()).isEqualTo(Statut.REFUSE);
			assertThat(r.raisons()).anyMatch(raison -> raison.contains("sha256"));
		});
		assertThat(actives).containsEntry("alpha", "1.0.0");
	}

	@Test
	void taille_differente_du_catalogue_rejetee() throws IOException {
		EntreeCatalogue vraie = publier("alpha", "1.1.0");
		source.catalogue.set(0, new EntreeCatalogue("alpha", "1.1.0", "chaud", "1.0.0", "2.0.0", vraie.sha256(),
				vraie.taille() - 1));

		tireur.tirer();

		assertThat(installes).isEmpty();
		assertThat(deposes()).isEmpty();
		assertThat(source.rapports).singleElement().satisfies(r -> assertThat(r.statut()).isEqualTo(Statut.REFUSE));
	}

	@Test
	void version_refusee_n_est_pas_retiree_au_tirage_suivant_mais_l_est_si_une_plus_haute_apparait()
			throws IOException {
		actives.put("alpha", "1.0.0");
		publier("alpha", "1.1.0");
		reponses.put("alpha", m -> m.version().equals("1.1.0")
				? resultat(Statut.REFUSE, m, "Dépendance absente : le plugin 'beta' n'est pas actif") : null);

		tireur.tirer();
		assertThat(source.telechargements).containsExactly("alpha-1.1.0");
		assertThat(source.rapports).singleElement().satisfies(r -> assertThat(r.statut()).isEqualTo(Statut.REFUSE));
		assertThat(tireur.misesDeCote()).containsEntry("alpha", "1.1.0");

		// Plusieurs tirages : la version refusée n'est ni retéléchargée ni rapportée à nouveau.
		tireur.tirer();
		tireur.tirer();
		assertThat(source.telechargements).containsExactly("alpha-1.1.0");
		assertThat(source.rapports).hasSize(1);

		// Une version plus haute apparaît : elle est tirée.
		reponses.remove("alpha");
		publier("alpha", "1.2.0");
		tireur.tirer();
		assertThat(source.telechargements).containsExactly("alpha-1.1.0", "alpha-1.2.0");
		assertThat(source.rapports).last().isEqualTo(new Rapport("alpha", "1.2.0", Statut.OK, List.of()));
		assertThat(tireur.misesDeCote()).doesNotContainKey("alpha");
	}

	@Test
	void statut_REFUSE_ANNULE_ECHEC_sont_rapportes_tels_quels() throws IOException {
		for (String nom : List.of("alpha", "beta", "gamma")) {
			actives.put(nom, "1.0.0");
			publier(nom, "1.1.0");
		}
		// alpha : mise à jour « coeur » (ne se fait qu'au redémarrage).
		EntreeCatalogue alpha = source.catalogue.remove(0);
		source.catalogue.add(0, new EntreeCatalogue("alpha", "1.1.0", "coeur", alpha.coeurMin(), alpha.coeurMax(),
				alpha.sha256(), alpha.taille()));
		actives.put("delta", "1.0.0");
		publier("delta", "1.1.0");
		reponses.put("alpha", m -> resultat(Statut.REFUSE, m, "Le manifeste déclare mise-a-jour=coeur"));
		reponses.put("beta", m -> resultat(Statut.ANNULE, m, "démarrage impossible", "ancienne version relancée"));
		reponses.put("gamma", m -> resultat(Statut.ECHEC, m, "retour arrière impossible"));
		reponses.put("delta", m -> resultat(Statut.REFUSE, m, "Dépendance absente : le plugin 'zeta' n'est pas actif"));

		tireur.tirer();

		assertThat(source.rapports).containsExactlyInAnyOrder(
				new Rapport("alpha", "1.1.0", Statut.REFUSE, List.of("Le manifeste déclare mise-a-jour=coeur")),
				new Rapport("beta", "1.1.0", Statut.ANNULE, List.of("démarrage impossible", "ancienne version relancée")),
				new Rapport("gamma", "1.1.0", Statut.ECHEC, List.of("retour arrière impossible")),
				new Rapport("delta", "1.1.0", Statut.REFUSE,
						List.of("Dépendance absente : le plugin 'zeta' n'est pas actif")));
		assertThat(source.rapports).noneMatch(r -> r.statut() == Statut.OK);
		// Seul le refus d'une mise à jour « à froid » (coeur, ou changement de majeure) laisse le jar pour le prochain
		// redémarrage ; ECHEC, ANNULE et les autres refus le retirent (il ferait tomber le plugin au démarrage).
		assertThat(deposes()).containsExactly("alpha-1.1.0.jar", "alpha-1.1.0.jar.sig");
	}

	@Test
	void refus_d_un_changement_de_majeure_garde_le_jar_pour_le_redemarrage() throws IOException {
		actives.put("alpha", "1.4.0");
		publier("alpha", "2.0.0");
		reponses.put("alpha", m -> resultat(Statut.REFUSE, m, "Changement de version majeure (1.4.0 -> 2.0.0)"));

		tireur.tirer();

		assertThat(source.rapports).singleElement().satisfies(r -> assertThat(r.statut()).isEqualTo(Statut.REFUSE));
		assertThat(deposes()).containsExactly("alpha-2.0.0.jar", "alpha-2.0.0.jar.sig");
		tireur.tirer();
		assertThat(source.telechargements).containsExactly("alpha-2.0.0");
	}

	@Test
	void refus_anonyme_de_l_installateur_retire_le_jar_depose() throws IOException {
		publier("alpha", "1.1.0");
		reponses.put("alpha", m -> new ResultatInstallation(Statut.REFUSE, null, null, List.of("signature absente"), 0));

		tireur.tirer();

		assertThat(source.rapports).containsExactly(
				new Rapport("alpha", "1.1.0", Statut.REFUSE, List.of("signature absente")));
		assertThat(deposes()).isEmpty();
	}

	@Test
	void installateur_qui_leve_est_un_ECHEC_rapporte_et_tirer_ne_leve_pas() throws IOException {
		publier("alpha", "1.1.0");
		reponses.put("alpha", m -> {
			throw new IllegalStateException("boum");
		});

		assertThatCode(tireur::tirer).doesNotThrowAnyException();

		assertThat(source.rapports).singleElement().satisfies(r -> assertThat(r.statut()).isEqualTo(Statut.ECHEC));
		assertThat(deposes()).isEmpty();
		assertThat(tireur.misesDeCote()).containsEntry("alpha", "1.1.0");
	}

	@Test
	void maitre_injoignable_ne_leve_pas_et_ne_modifie_rien() throws IOException {
		actives.put("alpha", "1.2.0");
		deposerAlaMain(jarSigne("alpha", "1.0.0", Map.of()));
		deposerAlaMain(jarSigne("alpha", "1.1.0", Map.of()));
		deposerAlaMain(jarSigne("alpha", "1.2.0", Map.of()));
		publier("beta", "1.0.0");
		reponses.put("beta", m -> resultat(Statut.REFUSE, m, "refus"));
		tireur.tirer(); // beta mis de côté ; alpha 1.0.0 supprimé (plus vieux que la précédente)
		TreeSet<String> avant = deposes();
		Map<String, String> misesDeCote = Map.copyOf(tireur.misesDeCote());
		int rapports = source.rapports.size();
		publier("alpha", "1.3.0");
		deposerAlaMain(jarSigne("alpha", "0.9.0", Map.of()));
		avant = deposes();

		for (Exception panne : List.<Exception>of(new ConnectException("Connection refused"),
				new java.net.http.HttpTimeoutException("délai dépassé"), new IllegalStateException("réponse illisible"))) {
			source.panne = panne;
			assertThatCode(tireur::tirer).doesNotThrowAnyException();
		}

		assertThat(source.telechargements).containsExactly("beta-1.0.0");
		assertThat(source.rapports).hasSize(rapports);
		assertThat(deposes()).isEqualTo(avant);
		assertThat(tireur.misesDeCote()).isEqualTo(misesDeCote);
		assertThat(actives).containsEntry("alpha", "1.2.0");
	}

	@Test
	void reponse_catalogue_absurde_est_ignoree() throws IOException {
		proprietes.setTailleMaxJarMo(1);
		Path jar = jarSigne("alpha", "1.1.0", Map.of());
		String sha = sha256(jar);
		long taille = Files.size(jar);
		source.jars.put("alpha-1.1.0", jar);
		List<EntreeCatalogue> absurdes = new ArrayList<>();
		absurdes.add(null);
		absurdes.add(new EntreeCatalogue(null, "1.1.0", "chaud", "1.0.0", null, sha, taille));
		absurdes.add(new EntreeCatalogue("../evil", "1.1.0", "chaud", "1.0.0", null, sha, taille));
		absurdes.add(new EntreeCatalogue("Alpha", "1.1.0", "chaud", "1.0.0", null, sha, taille));
		absurdes.add(new EntreeCatalogue("alpha", null, "chaud", "1.0.0", null, sha, taille));
		absurdes.add(new EntreeCatalogue("alpha", "1.1.0/../..", "chaud", "1.0.0", null, sha, taille));
		absurdes.add(new EntreeCatalogue("alpha", "1.1", "chaud", "1.0.0", null, sha, taille));
		absurdes.add(new EntreeCatalogue("alpha", "9.9.9", "chaud", "1.0.0", null, "zz", taille));
		absurdes.add(new EntreeCatalogue("alpha", "9.9.8", "chaud", "1.0.0", null, sha.toUpperCase(), taille));
		absurdes.add(new EntreeCatalogue("alpha", "9.9.7", "chaud", "1.0.0", null, sha, -1));
		absurdes.add(new EntreeCatalogue("alpha", "9.9.6", "chaud", "1.0.0", null, sha, 0));
		absurdes.add(new EntreeCatalogue("alpha", "9.9.5", "chaud", "1.0.0", null, sha, 2L * 1024 * 1024));
		absurdes.add(new EntreeCatalogue("alpha", "9.9.4", "chaud", "1.0.0", null, sha, Long.MAX_VALUE));
		absurdes.add(new EntreeCatalogue("alpha", "9.9.3", "chaud", null, null, sha, taille));
		absurdes.add(new EntreeCatalogue("alpha", "9.9.2", "chaud", "../1", null, sha, taille));
		absurdes.add(new EntreeCatalogue("alpha", "9.9.1", "n'importe", "1.0.0", null, sha, taille));
		absurdes.add(new EntreeCatalogue("alpha", "9.9.0", "chaud", "1.0.0", null, sha, taille));
		absurdes.add(new EntreeCatalogue("alpha", "9.9.0", "chaud", "1.0.0", null, "1".repeat(64), taille));
		absurdes.add(new EntreeCatalogue("alpha", "x".repeat(70), "chaud", "1.0.0", null, sha, taille));
		source.catalogue.addAll(absurdes);
		source.catalogue.add(new EntreeCatalogue("alpha", "1.1.0", "chaud", "1.0.0", "2.0.0", sha, taille));

		assertThatCode(tireur::tirer).doesNotThrowAnyException();

		// Seule l'entrée valide est tirée (les 9.9.x absurdes ou en double contradictoire sont écartées).
		assertThat(source.telechargements).containsExactly("alpha-1.1.0");
		assertThat(source.rapports).containsExactly(new Rapport("alpha", "1.1.0", Statut.OK, List.of()));

		// Catalogue nul, ou démesuré : ignoré sans lever.
		source.catalogueNul = true;
		assertThatCode(tireur::tirer).doesNotThrowAnyException();
		source.catalogueNul = false;
		source.catalogue.clear();
		for (int i = 0; i < 20_000; i++) {
			source.catalogue.add(new EntreeCatalogue("p" + i, "1.0.0", "chaud", "1.0.0", null, sha, taille));
		}
		assertThatCode(tireur::tirer).doesNotThrowAnyException();
		assertThat(source.telechargements).containsExactly("alpha-1.1.0");
	}

	@Test
	void jars_anciens_supprimes_sauf_active_et_precedente() throws IOException {
		actives.put("alpha", "1.2.0");
		for (String v : List.of("1.0.0", "1.0.1", "1.1.0", "1.2.0", "1.3.0")) {
			deposerAlaMain(jarSigne("alpha", v, Map.of()));
		}
		deposerAlaMain(jarSigne("beta", "1.0.0", Map.of())); // beta inactif : jamais touché
		Files.writeString(plugins.resolve("autre.jar"), "x"); // nom hors convention : jamais touché
		publier("alpha", "1.2.0");

		tireur.tirer();

		assertThat(deposes()).containsExactly("alpha-1.1.0.jar", "alpha-1.1.0.jar.sig", "alpha-1.2.0.jar",
				"alpha-1.2.0.jar.sig", "alpha-1.3.0.jar", "alpha-1.3.0.jar.sig", "autre.jar", "beta-1.0.0.jar",
				"beta-1.0.0.jar.sig");
		assertThat(source.telechargements).isEmpty();
	}

	@Test
	void manifeste_different_du_catalogue_est_ECHEC_et_rien_n_est_installe() throws IOException {
		// Fichier et catalogue disent 1.1.0, le manifeste signé dit 1.9.0.
		Path menteur = jarSigne("alpha", "1.1.0", Map.of("version", "1.9.0"));
		publier("alpha", "1.1.0", menteur, "1.0.0", "2.0.0");

		tireur.tirer();

		assertThat(installes).isEmpty();
		assertThat(deposes()).isEmpty();
		assertThat(source.rapports).singleElement().satisfies(r -> {
			assertThat(r.statut()).isEqualTo(Statut.ECHEC);
			assertThat(r.raisons()).anyMatch(raison -> raison.contains("manifeste"));
		});
	}

	@Test
	void resultat_de_l_installateur_d_un_autre_plugin_est_ECHEC() throws IOException {
		publier("alpha", "1.1.0");
		reponses.put("alpha", m -> new ResultatInstallation(Statut.OK, "beta", "1.1.0", List.of(), 0));

		tireur.tirer();

		assertThat(source.rapports).singleElement().satisfies(r -> {
			assertThat(r.nom()).isEqualTo("alpha");
			assertThat(r.statut()).isEqualTo(Statut.ECHEC);
		});
		assertThat(tireur.misesDeCote()).containsEntry("alpha", "1.1.0");
	}

	@Test
	void signature_invalide_rejetee_avant_tout_depot() throws IOException {
		Path jar = JarDePluginDeTest.signer(
				JarDePluginDeTest.creer(racine.resolve("pirate"), "alpha", "1.1.0", Map.of("coeur-min", "1.0.0",
						"mise-a-jour", "chaud", "paquet", "alpha", "configuration", "alpha.Config"), Map.of()),
				JarDePluginDeTest.nouvellePaire().getPrivate(), JarDePluginDeTest.KEY_ID_TEST);
		publier("alpha", "1.1.0", jar, "1.0.0", null);

		tireur.tirer();

		assertThat(installes).isEmpty();
		assertThat(deposes()).isEmpty();
		assertThat(source.rapports).singleElement().satisfies(r -> {
			assertThat(r.statut()).isEqualTo(Statut.REFUSE);
			assertThat(r.raisons()).anyMatch(raison -> raison.contains("signature"));
		});
	}

	@Test
	void version_incompatible_avec_le_coeur_ignoree() throws IOException {
		actives.put("alpha", "1.0.0");
		publier("alpha", "1.1.0");
		publier("alpha", "1.2.0", jarSigne("alpha", "1.2.0", Map.of("coeur-min", "2.0.0")), "2.0.0", null);
		publier("alpha", "1.3.0", jarSigne("alpha", "1.3.0", Map.of("coeur-max", "1.4.0")), "1.0.0", "1.4.0");

		tireur.tirer();

		// Plus haute version compatible avec le coeur 1.5.0 : 1.1.0.
		assertThat(source.telechargements).containsExactly("alpha-1.1.0");
		assertThat(source.rapports).containsExactly(new Rapport("alpha", "1.1.0", Statut.OK, List.of()));
	}

	@Test
	void fichier_deja_depose_n_est_jamais_ecrase() throws IOException {
		publier("alpha", "1.1.0");
		Files.createDirectories(plugins);
		Path existant = plugins.resolve("alpha-1.1.0.jar");
		Files.writeString(existant, "contenu dépose a la main");

		tireur.tirer();

		assertThat(Files.readString(existant)).isEqualTo("contenu dépose a la main");
		assertThat(installes).isEmpty();
		assertThat(source.rapports).singleElement().satisfies(r -> assertThat(r.statut()).isEqualTo(Statut.ECHEC));
	}

	@Test
	void fichier_identique_deja_depose_est_reutilise() throws IOException {
		EntreeCatalogue e = publier("alpha", "1.1.0");
		deposerAlaMain(source.jars.get("alpha-1.1.0"));

		tireur.tirer();

		assertThat(installes).containsExactly(plugins.resolve("alpha-1.1.0.jar").toAbsolutePath().normalize());
		assertThat(source.rapports).containsExactly(new Rapport("alpha", "1.1.0", Statut.OK, List.of()));
		assertThat(sha256(plugins.resolve("alpha-1.1.0.jar"))).isEqualTo(e.sha256());
	}

	@Test
	void chemin_telecharge_hors_du_dossier_de_tirage_refuse() throws IOException {
		publier("alpha", "1.1.0");
		source.cheminForce = jarSigne("alpha", "1.1.0", Map.of());

		tireur.tirer();

		assertThat(installes).isEmpty();
		assertThat(deposes()).isEmpty();
		assertThat(source.rapports).singleElement().satisfies(r -> assertThat(r.statut()).isNotEqualTo(Statut.OK));
	}

	@Test
	void rapport_en_echec_passager_est_retente_au_tirage_suivant() throws IOException {
		publier("alpha", "1.1.0");
		source.panneRapport = new ConnectException("Connection refused");

		tireur.tirer();
		assertThat(source.rapports).isEmpty();

		source.panneRapport = null;
		tireur.tirer();
		assertThat(source.rapports).containsExactly(new Rapport("alpha", "1.1.0", Statut.OK, List.of()));
		assertThat(source.telechargements).containsExactly("alpha-1.1.0");

		tireur.tirer();
		assertThat(source.rapports).hasSize(1);
	}

}
