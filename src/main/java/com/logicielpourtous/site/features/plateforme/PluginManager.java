package com.logicielpourtous.site.features.plateforme;

import com.logicielpourtous.site.features.plateforme.ResultatInstallation.Statut;
import com.logicielpourtous.site.sdk.ManifestePlugin;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Installe, met à jour (mode maintenance), désinstalle et démarre les plugins.
 * <p>
 * Mise à jour : 503 sur le plugin, retrait du contrat, drainage borné, fermeture de l'ancienne génération, migration
 * puis démarrage de la nouvelle ; en cas d'échec, l'ancienne version est relancée sur le schéma tel qu'il est (une
 * migration en échec est annulée par PostgreSQL ; une migration réussie suivie d'un contexte qui plante laisse le
 * schéma en avance, d'où la règle « l'ancienne version doit tourner sur le nouveau schéma »).
 * <p>
 * Toutes les opérations qui changent l'ensemble des générations sont sérialisées ({@code synchronized}).
 */
@Component
@ConditionalOnProperty(name = "app.plateforme.actif", havingValue = "true")
public class PluginManager {

	private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

	static final String TRAVAIL = ".work";

	private final PlateformeProperties proprietes;

	private final DemarreurContexte demarreur;

	private final RoutagePlugins routage;

	private final ContratRegistre contrats;

	private final ReferencesEntrantes references;

	private final JdbcTemplate jdbc;

	/** Nettoyage des caches qui retiennent un chargeur retiré ({@link NettoyeurFuites}). */
	private final Consumer<ClassLoader> nettoyage;

	/**
	 * Chargeur de chaque génération fermée, en référence FAIBLE seulement (mesure des fuites) : le gestionnaire ne
	 * doit jamais retenir lui-même un chargeur retiré.
	 */
	private final List<Retire> retires = new CopyOnWriteArrayList<>();

	private record Retire(String etiquette, WeakReference<ClassLoader> chargeur) {
	}

	private final Map<String, PluginGeneration> actifs = new ConcurrentHashMap<>();

	/** Manifeste de chaque génération active (même clé que {@link #actifs}). */
	private final Map<String, ManifestePlugin> manifestes = new ConcurrentHashMap<>();

	/**
	 * Version en cours de remplacement de chaque plugin en mise à jour : pendant le drainage et le démarrage de la
	 * nouvelle version, le plugin n'est plus dans {@link #actifs} mais doit rester visible (état MAINTENANCE).
	 */
	private final Map<String, String> enMiseAJour = new ConcurrentHashMap<>();

	public PluginManager(PlateformeProperties proprietes, DemarreurContexte demarreur, RoutagePlugins routage,
			ContratRegistre contrats, ReferencesEntrantes references, DataSource dataSource,
			Consumer<ClassLoader> nettoyage) {
		this.proprietes = proprietes;
		this.demarreur = demarreur;
		this.routage = routage;
		this.contrats = contrats;
		this.references = references;
		this.jdbc = new JdbcTemplate(dataSource);
		this.nettoyage = nettoyage;
		log.warn("""
				*****************************************************************************************
				PLATEFORME DE PLUGINS ACTIVE : jars non signés exécutés avec tous les privilèges JVM \
				(base, secrets, fichiers, réseau) — signature = sous-projet 2. \
				Voir docs/plateforme-plugins-local.md, « Avant de l'activer en production ».
				*****************************************************************************************""");
	}

	/** Générations actives par nom de plugin (instantané non modifiable). */
	public Map<String, PluginGeneration> actifs() {
		return Map.copyOf(actifs);
	}

	/**
	 * État de chaque plugin, trié par nom : {@link EtatPlugin.Etat#MAINTENANCE} pendant une mise à jour (route en 503),
	 * sinon {@link EtatPlugin.Etat#ACTIF}. Non sérialisé avec les installations : répond pendant une mise à jour.
	 */
	public List<EtatPlugin> etats() {
		Map<String, EtatPlugin> etats = new java.util.TreeMap<>();
		actifs.forEach((nom, gen) -> etats.put(nom, new EtatPlugin(nom, gen.version,
				routage.enMaintenance(nom) ? EtatPlugin.Etat.MAINTENANCE : EtatPlugin.Etat.ACTIF)));
		enMiseAJour.forEach((nom, version) -> etats.putIfAbsent(nom,
				new EtatPlugin(nom, version, EtatPlugin.Etat.MAINTENANCE)));
		return List.copyOf(etats.values());
	}

	/**
	 * Installe, pour chaque plugin, le {@code .jar} de plus haute version du répertoire des plugins (voir
	 * {@link #plusHautesVersions}), par ordre alphabétique de fichier. Un jar refusé ou en échec est
	 * journalisé et ignoré, jamais bloquant ; les autres fichiers et le sous-dossier {@code .work} sont ignorés. Les
	 * jars refusés sont retentés tant qu'un passage installe quelque chose (dépendance installée plus loin dans
	 * l'ordre alphabétique).
	 */
	@EventListener(ApplicationReadyEvent.class)
	public synchronized void demarrer() {
		// Une exception qui sortirait de cet écouteur arrêterait l'application : rien ne doit s'échapper.
		try {
			demarrerTousLesJars();
		}
		catch (Throwable e) {
			log.error("Démarrage des plugins interrompu : le cœur continue sans les plugins restants", e);
		}
	}

	private void demarrerTousLesJars() {
		Path repertoire = repertoire();
		if (!Files.isDirectory(repertoire)) {
			log.info("Répertoire des plugins absent ({}) : aucun plugin à démarrer", repertoire);
			return;
		}
		purgerTravail();
		List<Path> restants;
		try (Stream<Path> fichiers = Files.list(repertoire)) {
			restants = fichiers.filter(Files::isRegularFile)
				.filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
				.sorted((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()))
				.collect(java.util.stream.Collectors.toCollection(ArrayList::new));
		}
		catch (IOException e) {
			log.error("Lecture du répertoire des plugins impossible : {}", repertoire, e);
			return;
		}
		restants = plusHautesVersions(restants);
		Map<Path, ResultatInstallation> derniers = new LinkedHashMap<>();
		boolean progres = true;
		while (progres && !restants.isEmpty()) {
			progres = false;
			for (Path jar : List.copyOf(restants)) {
				ResultatInstallation r;
				try {
					r = installer(jar);
				}
				catch (Throwable e) {
					log.error("Démarrage du plugin {} impossible", jar.getFileName(), e);
					r = new ResultatInstallation(Statut.ECHEC, null, null, List.of(messageRacine(e)), 0);
				}
				derniers.put(jar, r);
				// Seul un refus d'un jar lisible (dépendance pas encore active...) mérite un nouveau passage.
				if (r.statut() != Statut.REFUSE || r.plugin() == null) {
					restants.remove(jar);
					progres |= r.statut() == Statut.OK;
				}
			}
		}
		derniers.forEach((jar, r) -> {
			if (r.statut() == Statut.OK) {
				log.info("Plugin démarré : {} {} ({})", r.plugin(), r.version(), jar.getFileName());
			}
			else {
				log.warn("Plugin ignoré au démarrage : {} -> {} {}", jar.getFileName(), r.statut(), r.raisons());
			}
		});
	}

	/**
	 * Un seul jar par plugin au démarrage : celui de plus haute version (semver, version du manifeste ; le nom de
	 * fichier ne compte pas). Démarrer les versions dans l'ordre des fichiers ferait des mises à jour à chaud
	 * successives, refusées pour une montée majeure ou {@code mise-a-jour=coeur}, et pourrait laisser une version
	 * inférieure active. Un jar illisible, ou deux jars de même plus haute version, sont journalisés et ignorés.
	 */
	private List<Path> plusHautesVersions(List<Path> jars) {
		Map<String, List<Path>> parNom = new LinkedHashMap<>();
		Map<Path, String> versions = new HashMap<>();
		for (Path jar : jars) {
			try {
				ManifestePlugin m = LecteurManifeste.lire(jar);
				parNom.computeIfAbsent(m.nom(), n -> new ArrayList<>()).add(jar);
				versions.put(jar, m.version());
			}
			catch (RuntimeException e) {
				log.warn("Plugin ignoré au démarrage : {} illisible ({})", jar.getFileName(), e.getMessage());
			}
		}
		List<Path> retenus = new ArrayList<>();
		parNom.forEach((nom, candidats) -> {
			String plusHaute = candidats.stream()
				.map(versions::get)
				.max(VerificateurMiseAJour::comparer)
				.orElseThrow();
			List<Path> meilleurs = candidats.stream()
				.filter(j -> VerificateurMiseAJour.comparer(versions.get(j), plusHaute) == 0)
				.toList();
			if (meilleurs.size() > 1) {
				log.warn("Plugin {} ignoré au démarrage : plusieurs jars de même plus haute version {} : {}", nom,
						plusHaute, meilleurs.stream().map(p -> p.getFileName().toString()).toList());
				return;
			}
			retenus.add(meilleurs.get(0));
			candidats.stream()
				.filter(j -> j != meilleurs.get(0))
				.forEach(j -> log.info("Plugin {} {} ignoré au démarrage : version {} présente ({})", nom,
						versions.get(j), plusHaute, j.getFileName()));
		});
		retenus.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
		return retenus;
	}

	/**
	 * Installe ou met à jour le plugin contenu dans {@code jar}. Le jar n'est jamais chargé directement : une copie
	 * dans {@code <repertoire>/.work/} est chargée (sous Windows un jar ouvert est verrouillé).
	 */
	public synchronized ResultatInstallation installer(Path jar) {
		ManifestePlugin m;
		Map<String, String> migrations;
		try {
			m = LecteurManifeste.lire(jar);
			migrations = LecteurManifeste.migrations(jar, m.nom());
		}
		catch (IllegalArgumentException e) {
			log.warn("Plugin refusé ({}) : {}", jar.getFileName(), e.getMessage());
			return new ResultatInstallation(Statut.REFUSE, null, null, List.of(e.getMessage()), 0);
		}
		PluginGeneration active = actifs.get(m.nom());

		List<String> raisons = new ArrayList<>(VerificateurMiseAJour.verifierPaquets(m));
		raisons.addAll(VerificateurMiseAJour.verifierDependances(m.dependances(), versionsActives(m.nom())));
		VerificateurMiseAJour.Verdict verdict = active == null
				? VerificateurMiseAJour.verifier(m, Map.of(), Set.of(), proprietes.getVersionCoeur(), null)
				: VerificateurMiseAJour.verifier(m, nonAppliquees(m.nom(), migrations), tablesReferencees(m.nom()),
						proprietes.getVersionCoeur(), active.version);
		raisons.addAll(verdict.raisons());
		if (!raisons.isEmpty()) {
			log.warn("Plugin {} {} refusé : {}", m.nom(), m.version(), raisons);
			return new ResultatInstallation(Statut.REFUSE, m.nom(), m.version(), raisons, 0);
		}

		Path copie;
		try {
			copie = copier(jar, m);
		}
		catch (IOException e) {
			log.error("Copie de travail de {} impossible", jar.getFileName(), e);
			return new ResultatInstallation(Statut.ECHEC, m.nom(), m.version(), List.of(messageRacine(e)), 0);
		}
		return active == null ? premiereInstallation(jar, copie, m) : miseAJour(jar, copie, m, active);
	}

	/** Retire le plugin (route, contrats, drainage, fermeture). Le schéma {@code plugin_<nom>} est conservé. */
	public synchronized boolean desinstaller(String nom) {
		PluginGeneration gen = actifs.remove(nom);
		if (gen == null) {
			return false;
		}
		manifestes.remove(nom);
		routage.retirerRoute(nom, gen);
		contrats.retirer(gen);
		drainer(gen);
		fermer(gen, true);
		log.info("Plugin {} désinstallé (schéma plugin_{} conservé)", gen.etiquette(), nom);
		return true;
	}

	/**
	 * Une erreur de la machine virtuelle (mémoire, pile) n'est pas un échec du plugin : elle est propagée après
	 * nettoyage. Toute autre erreur ({@code NoClassDefFoundError}, {@code LinkageError}... fréquentes avec un jar de
	 * plugin) est traitée comme un échec de la version.
	 */
	private static void propagerSiFatale(Throwable e) {
		if (e instanceof VirtualMachineError fatale) {
			throw fatale;
		}
	}

	private ResultatInstallation premiereInstallation(Path jar, Path copie, ManifestePlugin m) {
		PluginGeneration gen = new PluginGeneration(m.nom(), m.version(), copie);
		gen.source = jar.toAbsolutePath();
		boolean active = false;
		try {
			demarrerGeneration(gen, m);
			basculer(gen, m);
			active = true;
			log.info("Plugin {} installé", gen.etiquette());
			return new ResultatInstallation(Statut.OK, m.nom(), m.version(), List.of(), 0);
		}
		catch (Throwable e) {
			propagerSiFatale(e);
			log.error("Première installation de {} en échec", gen.etiquette(), e);
			return new ResultatInstallation(Statut.ECHEC, m.nom(), m.version(), List.of(messageRacine(e)), 0);
		}
		finally {
			if (!active) {
				fermer(gen, true); // aucune génération à demi démarrée, copie supprimée
			}
		}
	}

	/** Port de {@code deployMaintenance} du prototype (stratégie maintenance seulement). */
	private ResultatInstallation miseAJour(Path jar, Path copie, ManifestePlugin m, PluginGeneration ancienne) {
		String nom = m.nom();
		ManifestePlugin manifesteAncien = manifestes.get(nom);
		long debut = System.nanoTime();
		Statut statut = Statut.ECHEC;
		List<String> raisons = new ArrayList<>();
		enMiseAJour.put(nom, ancienne.version);
		routage.maintenance(nom, true); // à partir d'ici : 503 + Retry-After sur /api/plugins/<nom>/**
		try {
			// Retrait de la route : le filtre ne compte plus de nouvelle requête sur l'ancienne génération (le routage
			// répond 503 tant que la maintenance dure) ; retrait du contrat : les dépendants reçoivent
			// ContratIndisponibleException. Le drainage n'attend donc que ce qui était déjà en vol.
			routage.retirerRoute(nom, ancienne);
			contrats.retirer(ancienne);
			drainer(ancienne);
			actifs.remove(nom, ancienne);
			manifestes.remove(nom);
			// La copie de l'ancienne version est conservée jusqu'à l'issue : elle sert au retour arrière.
			fermer(ancienne, false);

			PluginGeneration gen = new PluginGeneration(nom, m.version(), copie);
			gen.source = jar.toAbsolutePath();
			boolean nouvelleActive = false;
			try {
				demarrerGeneration(gen, m);
				basculer(gen, m);
				nouvelleActive = true;
				statut = Statut.OK;
				log.info("Plugin {} mis à jour vers {}", ancienne.etiquette(), gen.etiquette());
			}
			catch (Throwable e) {
				propagerSiFatale(e);
				log.error("Mise à jour de {} vers {} en échec : retour à l'ancienne version", ancienne.etiquette(),
						gen.etiquette(), e);
				raisons.add(messageRacine(e));
			}
			finally {
				if (!nouvelleActive) {
					fermer(gen, true);
				}
			}
			if (!nouvelleActive) {
				statut = relancer(ancienne, manifesteAncien, raisons);
			}
		}
		finally {
			supprimerCopie(ancienne.jarCopie);
			routage.maintenance(nom, false);
			enMiseAJour.remove(nom);
		}
		long interruptionMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - debut);
		return new ResultatInstallation(statut, nom, m.version(), raisons, interruptionMs);
	}

	/** Relance l'ancienne version depuis sa copie de travail conservée : {@code ANNULE}, ou {@code ECHEC} si ça rate. */
	private Statut relancer(PluginGeneration ancienne, ManifestePlugin m, List<String> raisons) {
		PluginGeneration retour = null;
		boolean relancee = false;
		try {
			Path copie = copier(ancienne.jarCopie, m);
			retour = new PluginGeneration(m.nom(), m.version(), copie);
			retour.source = ancienne.source;
			demarrerGeneration(retour, m);
			basculer(retour, m);
			relancee = true;
			log.warn("Plugin {} relancé après l'échec de la mise à jour", retour.etiquette());
			return Statut.ANNULE;
		}
		catch (Throwable e) {
			propagerSiFatale(e);
			log.error("Retour arrière de {} impossible : le plugin n'est plus actif", ancienne.etiquette(), e);
			raisons.add("Retour arrière impossible : " + messageRacine(e));
			return Statut.ECHEC;
		}
		finally {
			if (!relancee && retour != null) {
				fermer(retour, true);
			}
		}
	}

	/** Chargeur, migrations, contexte enfant et mapping MVC propre de la génération. */
	private void demarrerGeneration(PluginGeneration gen, ManifestePlugin m) throws MalformedURLException {
		gen.loader = new PluginClassLoader(gen.etiquette(), gen.jarCopie.toUri().toURL(),
				PluginManager.class.getClassLoader());
		demarreur.migrer(gen, m);
		demarreur.demarrer(gen, m);
		RoutagePlugins.creerMapping(gen);
	}

	/**
	 * Déclare la génération au registre de contrats (publication immédiate des contrats déjà connus, résolution
	 * paresseuse des autres), puis la rend active et routée.
	 */
	private void basculer(PluginGeneration gen, ManifestePlugin m) {
		contrats.activer(gen);
		actifs.put(m.nom(), gen);
		manifestes.put(m.nom(), m);
		routage.router(m.nom(), gen);
	}

	/**
	 * Attend que les requêtes et appels de contrat en vol sur la génération se terminent, au plus
	 * {@code delaiDrainageMs} ; au-delà, la fermeture est forcée et journalisée.
	 */
	private void drainer(PluginGeneration gen) {
		long delai = proprietes.getDelaiDrainageMs();
		long limite = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delai);
		while (gen.enVol.get() > 0 && System.nanoTime() < limite) {
			try {
				Thread.sleep(2);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		int restant = gen.enVol.get();
		if (restant > 0) {
			log.warn("Drainage de {} incomplet après {} ms : {} requête(s) ou appel(s) encore en vol, fermeture forcée",
					gen.etiquette(), delai, restant);
		}
	}

	/**
	 * Ferme une génération : route et contrats retirés, contexte fermé avec le chargeur du plugin comme TCCL,
	 * nettoyage des caches, fermeture du chargeur, copie du jar supprimée (si demandé), références coupées.
	 */
	private void fermer(PluginGeneration gen, boolean supprimerJar) {
		if (actifs.remove(gen.nom, gen)) {
			manifestes.remove(gen.nom); // échec après une bascule partielle
		}
		routage.retirerRoute(gen.nom, gen);
		contrats.retirer(gen);
		ConfigurableApplicationContext contexte = gen.contexte;
		if (contexte != null) {
			Thread courant = Thread.currentThread();
			ClassLoader precedent = courant.getContextClassLoader();
			courant.setContextClassLoader(gen.loader);
			try {
				contexte.close();
			}
			catch (RuntimeException e) {
				log.warn("Fermeture du contexte de {} en erreur", gen.etiquette(), e);
			}
			finally {
				courant.setContextClassLoader(precedent);
			}
		}
		gen.contexte = null;
		gen.mapping = null;
		PluginClassLoader loader = gen.loader;
		if (loader != null) {
			try {
				nettoyage.accept(loader);
			}
			catch (RuntimeException e) {
				log.warn("Nettoyage des caches de {} en erreur", gen.etiquette(), e);
			}
			try {
				loader.close();
			}
			catch (IOException e) {
				log.warn("Fermeture du chargeur de {} en erreur", gen.etiquette(), e);
			}
			retires.add(new Retire(gen.etiquette(), new WeakReference<>(loader)));
		}
		gen.loader = null;
		if (supprimerJar) {
			supprimerCopie(gen.jarCopie);
		}
	}

	/**
	 * Combien de générations fermées sont encore en mémoire (port de {@code leakReport} du prototype). Avec
	 * {@code pressionSouple}, la mémoire est d'abord remplie jusqu'à {@code OutOfMemoryError} : la JVM garantit
	 * d'effacer toutes les {@code SoftReference} avant, sans quoi les caches « souples » de Spring fausseraient la
	 * mesure. Puis plusieurs GC. Coûteux et brutal pour les autres threads : réservé aux tests et au diagnostic, donc
	 * n'a lieu que si {@code app.plateforme.diagnostic-fuites=true} ; sinon la demande est ignorée (avertissement).
	 */
	public RapportFuites rapportFuites(boolean pressionSouple) {
		if (pressionSouple && !proprietes.isDiagnosticFuites()) {
			log.warn("Pression mémoire demandée mais app.plateforme.diagnostic-fuites=false : ignorée, rapport avec de "
					+ "simples GC (les caches en références souples peuvent compter des générations comme vivantes)");
		}
		else if (pressionSouple) {
			exercerPressionMemoire();
		}
		for (int i = 0; i < 5; i++) {
			System.gc();
			try {
				Thread.sleep(100);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		List<String> vivants = new ArrayList<>();
		for (Retire r : retires) {
			if (r.chargeur().get() != null) {
				vivants.add(r.etiquette());
			}
		}
		long metaspaceMo = ManagementFactory.getMemoryPoolMXBeans()
			.stream()
			.filter(p -> p.getName().equals("Metaspace"))
			.findFirst()
			.map(p -> p.getUsage().getUsed() / (1024 * 1024))
			.orElse(-1L);
		return new RapportFuites(retires.size(), vivants.size(), vivants, metaspaceMo);
	}

	@SuppressWarnings({ "unused", "UnusedAssignment" })
	private static void exercerPressionMemoire() {
		List<byte[]> blocs = new ArrayList<>();
		try {
			while (true) {
				blocs.add(new byte[8 * 1024 * 1024]);
			}
		}
		catch (OutOfMemoryError attendue) {
			blocs = null; // libère tout de suite : les SoftReference ont été effacées avant l'erreur
		}
	}

	private void supprimerCopie(Path copie) {
		try {
			Files.deleteIfExists(copie);
		}
		catch (IOException e) {
			log.warn("Copie de travail non supprimable : {}", copie, e);
		}
	}

	private Path repertoire() {
		return Path.of(proprietes.getRepertoirePlugins()).toAbsolutePath();
	}

	private Path copier(Path jar, ManifestePlugin m) throws IOException {
		Path travail = repertoire().resolve(TRAVAIL);
		Files.createDirectories(travail);
		Path copie = Files.createTempFile(travail, m.nom() + "-" + m.version() + "-", ".jar");
		Files.copy(jar, copie, StandardCopyOption.REPLACE_EXISTING);
		return copie;
	}

	/** Supprime les copies laissées par un arrêt brutal (au démarrage, aucune n'est chargée). */
	private synchronized void purgerTravail() {
		Path travail = repertoire().resolve(TRAVAIL);
		if (!Files.isDirectory(travail)) {
			return;
		}
		try (Stream<Path> fichiers = Files.list(travail)) {
			fichiers.filter(Files::isRegularFile).forEach(this::supprimerCopie);
		}
		catch (IOException e) {
			log.warn("Purge de {} impossible", travail, e);
		}
	}

	private Map<String, String> versionsActives(String saufNom) {
		Map<String, String> versions = new HashMap<>();
		actifs.forEach((nom, gen) -> {
			if (!nom.equals(saufNom)) {
				versions.put(nom, gen.version);
			}
		});
		return versions;
	}

	/**
	 * Tables qu'une migration à chaud de {@code nom} ne doit pas détruire : cibles de clés étrangères venant d'un autre
	 * schéma (catalogue PostgreSQL) et tables déclarées référencées par les AUTRES plugins actifs.
	 */
	private Set<String> tablesReferencees(String nom) {
		Set<String> tables = new HashSet<>(references.ciblesInterSchemas("plugin_" + nom));
		manifestes.forEach((autre, manifeste) -> {
			if (!autre.equals(nom)) {
				tables.addAll(manifeste.tablesReferencees());
			}
		});
		return tables;
	}

	/**
	 * Migrations du jar pas encore appliquées avec succès (selon l'historique Flyway du plugin) : seules celles-ci
	 * sont analysées pour une mise à jour à chaud. Le nom est validé par le manifeste ({@code ^[a-z][a-z0-9_]*$}).
	 */
	private Map<String, String> nonAppliquees(String nom, Map<String, String> migrations) {
		String historique = "plugin_" + nom + ".flyway_schema_history_" + nom;
		String existe = jdbc.queryForObject("SELECT to_regclass(?)::text", String.class, historique);
		if (existe == null) {
			return migrations;
		}
		Set<String> appliquees = new HashSet<>(
				jdbc.queryForList("SELECT script FROM " + historique + " WHERE success", String.class));
		Map<String, String> resultat = new LinkedHashMap<>(migrations);
		resultat.keySet().removeAll(appliquees);
		return resultat;
	}

	private static String messageRacine(Throwable e) {
		StringBuilder sb = new StringBuilder();
		for (Throwable c = e; c != null; c = c.getCause() == c ? null : c.getCause()) {
			if (!sb.isEmpty()) {
				sb.append(" | ");
			}
			String message = c.getMessage() == null ? "" : c.getMessage().lines().findFirst().orElse("");
			sb.append(c.getClass().getSimpleName()).append(": ").append(message);
		}
		return sb.toString();
	}

}
