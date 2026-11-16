package com.logicielpourtous.site.features.plateforme;

import com.logicielpourtous.site.features.plateforme.ResultatInstallation.Statut;
import com.logicielpourtous.site.sdk.ManifestePlugin;
import com.logicielpourtous.site.sdk.SignatureJar;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.net.MalformedURLException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.EnumSet;
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
 * <p>
 * Signature ({@code app.plateforme.signature-obligatoire}, vrai par défaut) : un jar n'est installé que si sa
 * {@code <jar>.sig} est valide pour une des {@link ClesPubliques}. La vérification porte sur la copie privée qui sera
 * chargée, avant toute lecture du manifeste ou ouverture par un chargeur de classes. Cette copie vit dans un dossier
 * de travail PRIVÉ ({@link #repertoireTravail()}, hors du répertoire de dépôt par défaut) : appartenant à
 * l'utilisateur du processus, restreint à lui seul (POSIX {@code rwx------}, ACL Windows propriétaire seul), refusé
 * s'il est un lien ou s'il appartient à un autre. C'est cette privauté qui garantit qu'entre la vérification et
 * l'ouverture par le chargeur (paresseuse) personne d'autre ne peut remplacer les octets vérifiés ; le dossier parent
 * ne doit pas non plus être modifiable par d'autres. Un retour arrière revérifie sa recopie avant de la charger.
 */
@Component
@ConditionalOnProperty(name = "app.plateforme.actif", havingValue = "true")
public class PluginManager {

	private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

	/** Suffixe du dossier de travail par défaut, frère du répertoire des plugins : {@code <repertoire>.work}. */
	static final String SUFFIXE_TRAVAIL = ".work";

	/**
	 * Propriétaire attendu du dossier de travail : celui des fichiers que crée ce processus (calculé au premier
	 * usage ; remplaçable par les tests pour simuler un dossier étranger).
	 */
	private volatile UserPrincipal proprietaireAttendu;

	/** Taille maximale lue d'une {@code .sig} (le JSON attendu fait une centaine d'octets). */
	static final int TAILLE_MAX_SIG = 16 * 1024;

	private final PlateformeProperties proprietes;

	/** Clés de confiance pour la signature des jars. */
	private final ClesPubliques cles;

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

	/**
	 * @param cles clés de confiance : le bean lu dans la ressource du cœur, ou des clés de test injectées
	 * @throws IllegalStateException si la signature est obligatoire et qu'aucune clé n'est disponible (jamais de cœur
	 * qui accepterait tout jar)
	 */
	public PluginManager(PlateformeProperties proprietes, ClesPubliques cles, DemarreurContexte demarreur,
			RoutagePlugins routage, ContratRegistre contrats, ReferencesEntrantes references, DataSource dataSource,
			Consumer<ClassLoader> nettoyage) {
		if (proprietes.isSignatureObligatoire() && (cles == null || cles.cles().isEmpty())) {
			throw new IllegalStateException("Plateforme de plugins : aucune clé publique de confiance alors que "
					+ "app.plateforme.signature-obligatoire=true : la plateforme refuse de démarrer");
		}
		this.proprietes = proprietes;
		this.cles = cles == null ? new ClesPubliques(List.of()) : cles;
		this.demarreur = demarreur;
		this.routage = routage;
		this.contrats = contrats;
		this.references = references;
		this.jdbc = new JdbcTemplate(dataSource);
		this.nettoyage = nettoyage;
		if (proprietes.isSignatureObligatoire()) {
			log.info("Plateforme de plugins active : signature obligatoire ({} clé(s) de confiance)",
					this.cles.cles().size());
		}
		else {
			log.warn("""
					*****************************************************************************************
					PLATEFORME DE PLUGINS ACTIVE avec app.plateforme.signature-obligatoire=false : jars non signés \
					(signature non vérifiée) exécutés avec tous les privilèges JVM (base, secrets, fichiers, \
					réseau). Réservé au développement local et aux tests, JAMAIS en production.
					*****************************************************************************************""");
		}
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
	 * journalisé et ignoré, jamais bloquant ; les autres fichiers et sous-dossiers sont ignorés. Les
	 * jars refusés sont retentés tant qu'un passage installe quelque chose (dépendance installée plus loin dans
	 * l'ordre alphabétique). Avec la signature obligatoire, les jars sans signature valide sont écartés d'abord
	 * (avertissement), avant toute lecture de leur manifeste : la plus haute version retenue est la plus haute
	 * VALIDEMENT SIGNÉE, lue dans la copie vérifiée ({@link #candidats}).
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
		restants = plusHautesVersions(candidats(restants));
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

	/** Jar déposé retenu au démarrage, avec le manifeste lu dans SA copie vérifiée (jamais dans le fichier déposé). */
	private record Candidat(Path jar, ManifestePlugin manifeste) {
	}

	/**
	 * Lit chaque jar déposé dans une copie privée bornée (octets réellement lus, au plus {@code taille-max-jar-mo}),
	 * supprimée aussitôt : signature vérifiée sur cette copie (si obligatoire), PUIS manifeste lu dans cette même
	 * copie. Le choix de version ne porte donc que sur des jars à signature valide, et sur les octets vérifiés : un
	 * remplacement du fichier déposé après la vérification ne peut pas fausser le choix. Un jar refusé, illisible ou de
	 * version non comparable est écarté seul (avertissement). {@link #installer} refait la vérification sur la copie
	 * qu'il chargera : ce tri n'est qu'un filtre, pas la garantie.
	 */
	private List<Candidat> candidats(List<Path> jars) {
		List<Candidat> candidats = new ArrayList<>();
		for (Path jar : jars) {
			String raison = null;
			ManifestePlugin m = null;
			Path copie = null;
			try {
				String signature = proprietes.isSignatureObligatoire() ? lireSignature(jar) : null;
				copie = copier(jar, prefixeCopie(jar));
				if (proprietes.isSignatureObligatoire()) {
					SignatureJar.Verdict verdict = SignatureJar.verifier(copie, signature, cles.cles());
					raison = verdict.valide() ? null : "signature invalide : " + verdict.raison();
				}
				if (raison == null) {
					apresVerificationAuDemarrage.accept(jar);
					m = LecteurManifeste.lire(copie);
					// Version comparable, sinon seul ce jar est écarté (jamais tout le démarrage).
					VerificateurMiseAJour.comparer(m.version(), m.version());
				}
			}
			catch (SignatureIllisible | JarTropVolumineux e) {
				raison = e.getMessage();
			}
			catch (IOException e) {
				raison = "jar illisible : " + messageRacine(e);
			}
			catch (RuntimeException e) {
				raison = "illisible (" + e.getMessage() + ")";
			}
			finally {
				if (copie != null) {
					supprimerCopie(copie);
				}
			}
			if (raison == null) {
				candidats.add(new Candidat(jar, m));
			}
			else {
				log.warn("Plugin ignoré au démarrage : {} -> {}", jar.getFileName(), raison);
			}
		}
		return candidats;
	}

	/**
	 * Un seul jar par plugin au démarrage : celui de plus haute version (semver, version du manifeste vérifié ; le nom
	 * de fichier ne compte pas). Démarrer les versions dans l'ordre des fichiers ferait des mises à jour à chaud
	 * successives, refusées pour une montée majeure, {@code mise-a-jour=coeur} ou une rétrogradation, et pourrait
	 * laisser une version inférieure active. Deux jars de même plus haute version sont journalisés et ignorés ; une
	 * erreur de comparaison n'écarte que le plugin concerné.
	 */
	private List<Path> plusHautesVersions(List<Candidat> candidats) {
		Map<String, List<Candidat>> parNom = new LinkedHashMap<>();
		for (Candidat c : candidats) {
			parNom.computeIfAbsent(c.manifeste().nom(), n -> new ArrayList<>()).add(c);
		}
		List<Path> retenus = new ArrayList<>();
		parNom.forEach((nom, liste) -> {
			try {
				String plusHaute = liste.stream()
					.map(c -> c.manifeste().version())
					.max(VerificateurMiseAJour::comparer)
					.orElseThrow();
				List<Candidat> meilleurs = liste.stream()
					.filter(c -> VerificateurMiseAJour.comparer(c.manifeste().version(), plusHaute) == 0)
					.toList();
				if (meilleurs.size() > 1) {
					log.warn("Plugin {} ignoré au démarrage : plusieurs jars de même plus haute version {} : {}", nom,
							plusHaute, meilleurs.stream().map(c -> c.jar().getFileName().toString()).toList());
					return;
				}
				Candidat retenu = meilleurs.get(0);
				retenus.add(retenu.jar());
				liste.stream()
					.filter(c -> c != retenu)
					.forEach(c -> log.info("Plugin {} {} ignoré au démarrage : version {} présente ({})", nom,
							c.manifeste().version(), plusHaute, c.jar().getFileName()));
			}
			catch (RuntimeException e) {
				log.warn("Plugin {} ignoré au démarrage : versions non comparables ({})", nom, e.getMessage());
			}
		});
		retenus.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
		return retenus;
	}

	/**
	 * Installe ou met à jour le plugin contenu dans {@code jar}. Le jar n'est jamais chargé directement : il est d'abord
	 * copié (taille bornée) dans le dossier de travail privé ({@link #repertoireTravail()}), et c'est cette copie
	 * qui est vérifiée puis chargée : le fichier déposé peut changer ensuite sans effet, et personne d'autre ne peut
	 * modifier la copie (dossier privé, voir la Javadoc de la classe). Sous Windows un jar ouvert est verrouillé.
	 * Avec la signature obligatoire, la {@code <jar>.sig} adjacente est lue une fois en mémoire et vérifiée sur la copie
	 * AVANT toute lecture du manifeste ; un refus ne laisse aucune copie ni aucun schéma. Une version strictement
	 * inférieure à la version active est refusée ({@code REFUSE}, « rétrogradation refusée ») ; à égalité, la
	 * réinstallation reste admise.
	 */
	public synchronized ResultatInstallation installer(Path jar) {
		String signature = null;
		if (proprietes.isSignatureObligatoire()) {
			try {
				signature = lireSignature(jar);
			}
			catch (SignatureIllisible e) {
				log.warn("Plugin refusé ({}) : {}", jar.getFileName(), e.getMessage());
				return refusAnonyme(e.getMessage());
			}
		}
		Path copie;
		try {
			copie = copier(jar, prefixeCopie(jar));
		}
		catch (JarTropVolumineux e) {
			log.warn("Plugin refusé ({}) : {}", jar.getFileName(), e.getMessage());
			return refusAnonyme(e.getMessage());
		}
		catch (IOException e) {
			log.error("Copie de travail de {} impossible", jar.getFileName(), e);
			return new ResultatInstallation(Statut.ECHEC, null, null, List.of(messageRacine(e)), 0);
		}
		boolean copieConfiee = false;
		try {
			if (proprietes.isSignatureObligatoire()) {
				SignatureJar.Verdict verdict = SignatureJar.verifier(copie, signature, cles.cles());
				if (!verdict.valide()) {
					String raison = "signature invalide : " + verdict.raison();
					log.warn("Plugin refusé ({}) : {}", jar.getFileName(), raison);
					return refusAnonyme(raison);
				}
				log.info("Signature de {} valide (clé {})", jar.getFileName(), verdict.keyId());
			}
			else {
				log.warn("Plugin {} : signature non vérifiée (app.plateforme.signature-obligatoire=false)",
						jar.getFileName());
			}

			ManifestePlugin m;
			Map<String, String> migrations;
			try {
				m = LecteurManifeste.lire(copie);
				migrations = LecteurManifeste.migrations(copie, m.nom());
			}
			catch (IllegalArgumentException e) {
				log.warn("Plugin refusé ({}) : {}", jar.getFileName(), e.getMessage());
				return refusAnonyme(e.getMessage());
			}
			PluginGeneration active = actifs.get(m.nom());

			List<String> raisons = new ArrayList<>(VerificateurMiseAJour.verifierPaquets(m));
			raisons.addAll(VerificateurMiseAJour.verifierDependances(m.dependances(), versionsActives(m.nom())));
			VerificateurMiseAJour.Verdict verdict = active == null
					? VerificateurMiseAJour.verifier(m, Map.of(), Set.of(), proprietes.getVersionCoeur(), null)
					: VerificateurMiseAJour.verifier(m, nonAppliquees(m.nom(), migrations),
							tablesReferencees(m.nom()), proprietes.getVersionCoeur(), active.version);
			raisons.addAll(verdict.raisons());
			if (!raisons.isEmpty()) {
				log.warn("Plugin {} {} refusé : {}", m.nom(), m.version(), raisons);
				return new ResultatInstallation(Statut.REFUSE, m.nom(), m.version(), raisons, 0);
			}
			copieConfiee = true; // désormais à la charge de la génération (supprimée à sa fermeture)
			return active == null ? premiereInstallation(jar, copie, m, signature)
					: miseAJour(jar, copie, m, active, signature);
		}
		finally {
			if (!copieConfiee) {
				supprimerCopie(copie);
			}
		}
	}

	private static ResultatInstallation refusAnonyme(String raison) {
		return new ResultatInstallation(Statut.REFUSE, null, null, List.of(raison), 0);
	}

	/** Signature adjacente absente, illisible ou trop grosse : refus sans rien copier ni lire du jar. */
	private static final class SignatureIllisible extends Exception {

		SignatureIllisible(String message) {
			super(message, null, false, false);
		}

	}

	/** Jar au-delà de {@code app.plateforme.taille-max-jar-mo} : refus avant toute vérification. */
	private static final class JarTropVolumineux extends IOException {

		JarTropVolumineux(String message) {
			super(message);
		}

	}

	/** Lit {@code <jar>.sig} une seule fois, en mémoire, taille bornée (texte UTF-8 strict). */
	private static String lireSignature(Path jar) throws SignatureIllisible {
		Path sig = jar.resolveSibling(jar.getFileName() + ".sig");
		if (!Files.exists(sig, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(sig)) {
			throw new SignatureIllisible("signature absente");
		}
		// Fichier ordinaire, sans suivre de lien : ni lien, ni dossier, ni tube (qui bloquerait la lecture).
		if (!Files.isRegularFile(sig, LinkOption.NOFOLLOW_LINKS)) {
			throw new SignatureIllisible("signature illisible : la .sig n'est pas un fichier ordinaire");
		}
		byte[] octets;
		try (InputStream in = Files.newInputStream(sig, LinkOption.NOFOLLOW_LINKS)) {
			octets = in.readNBytes(TAILLE_MAX_SIG + 1);
		}
		catch (IOException e) {
			throw new SignatureIllisible("signature illisible : lecture impossible");
		}
		if (octets.length > TAILLE_MAX_SIG) {
			throw new SignatureIllisible("signature illisible : plus de " + TAILLE_MAX_SIG + " octets");
		}
		try {
			return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(octets)).toString();
		}
		catch (CharacterCodingException e) {
			throw new SignatureIllisible("signature illisible : texte UTF-8 invalide");
		}
	}

	/** Préfixe lisible de la copie de travail, tiré du nom du fichier déposé (caractères sûrs seulement). */
	private static String prefixeCopie(Path jar) {
		String nom = jar.getFileName().toString().replaceFirst("(?i)\\.jar$", "").replaceAll("[^A-Za-z0-9._-]", "_");
		if (nom.length() > 60) {
			nom = nom.substring(0, 60);
		}
		return (nom.isEmpty() ? "plugin" : nom) + "-";
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

	private ResultatInstallation premiereInstallation(Path jar, Path copie, ManifestePlugin m, String signature) {
		PluginGeneration gen = new PluginGeneration(m.nom(), m.version(), copie);
		gen.source = jar.toAbsolutePath();
		gen.signature = signature;
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
	private ResultatInstallation miseAJour(Path jar, Path copie, ManifestePlugin m, PluginGeneration ancienne,
			String signature) {
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
			gen.signature = signature;
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
			Path copie = copier(ancienne.jarCopie, m.nom() + "-" + m.version() + "-");
			retour = new PluginGeneration(m.nom(), m.version(), copie);
			retour.source = ancienne.source;
			retour.signature = ancienne.signature;
			// La copie conservée a quitté le contrôle d'un chargeur ouvert : la recopie est revérifiée avant chargement.
			if (proprietes.isSignatureObligatoire()) {
				SignatureJar.Verdict verdict = SignatureJar.verifier(copie, ancienne.signature, cles.cles());
				if (!verdict.valide()) {
					throw new IOException("signature invalide : " + verdict.raison());
				}
			}
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
		return Path.of(proprietes.getRepertoirePlugins()).toAbsolutePath().normalize();
	}

	/**
	 * Copie {@code source} dans le dossier de travail privé en lisant au plus {@code taille-max-jar-mo} : au-delà, la
	 * copie partielle est supprimée et {@link JarTropVolumineux} levée (la borne vaut pour les octets réellement lus,
	 * pas pour une taille annoncée qui pourrait changer entre-temps).
	 */
	private Path copier(Path source, String prefixe) throws IOException {
		long max = proprietes.getTailleMaxJarMo() * 1024 * 1024;
		Path travail = travailPrive();
		// Fichier ordinaire seulement (un tube bloquerait la lecture sous le verrou de l'installation).
		if (!Files.isRegularFile(source)) {
			throw new IOException("jar illisible : pas un fichier ordinaire");
		}
		Path copie = Files.createTempFile(travail, prefixe, ".jar");
		boolean ok = false;
		try (InputStream in = Files.newInputStream(source);
				OutputStream out = Files.newOutputStream(copie, LinkOption.NOFOLLOW_LINKS)) {
			byte[] tampon = new byte[64 * 1024];
			long total = 0;
			int lus;
			while ((lus = in.read(tampon)) != -1) {
				total += lus;
				if (total > max) {
					throw new JarTropVolumineux("jar trop volumineux : plus de " + proprietes.getTailleMaxJarMo()
							+ " Mo (app.plateforme.taille-max-jar-mo)");
				}
				out.write(tampon, 0, lus);
			}
			ok = true;
			return copie;
		}
		finally {
			if (!ok) {
				supprimerCopie(copie);
			}
		}
	}

	/** Supprime les copies laissées par un arrêt brutal (au démarrage, aucune n'est chargée). */
	private synchronized void purgerTravail() {
		Path travail = repertoireTravail();
		if (!Files.exists(travail, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(travail)) {
			return;
		}
		try {
			travail = travailPrive(); // jamais de purge à travers un lien ou dans un dossier étranger
		}
		catch (IOException e) {
			log.error("Purge du dossier de travail refusée : {}", e.getMessage());
			return;
		}
		try (Stream<Path> fichiers = Files.list(travail)) {
			fichiers.filter(f -> Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)).forEach(this::supprimerCopie);
		}
		catch (IOException e) {
			log.warn("Purge de {} impossible", travail, e);
		}
	}

	/**
	 * Dossier de travail : {@code app.plateforme.repertoire-travail}, ou par défaut le frère
	 * {@code <repertoire-plugins>.work} (hors du répertoire de dépôt). Chemin absolu normalisé.
	 */
	Path repertoireTravail() {
		String configure = proprietes.getRepertoireTravail();
		if (configure != null && !configure.isBlank()) {
			return Path.of(configure).toAbsolutePath().normalize();
		}
		Path repertoire = repertoire();
		return repertoire.getFileName() == null ? repertoire.resolve(SUFFIXE_TRAVAIL)
				: repertoire.resolveSibling(repertoire.getFileName() + SUFFIXE_TRAVAIL);
	}

	/** Appelé au démarrage avec le jar déposé, juste après la vérification de sa copie (tests seulement). */
	private volatile Consumer<Path> apresVerificationAuDemarrage = jar -> {
	};

	/** Pour les tests : agit sur le jar déposé entre la vérification de sa copie et la suite du démarrage. */
	void apresVerificationAuDemarrage(Consumer<Path> action) {
		this.apresVerificationAuDemarrage = action;
	}

	/** Pour les tests : simule un dossier de travail appartenant à un autre utilisateur. */
	void proprietaireAttendu(UserPrincipal proprietaire) {
		this.proprietaireAttendu = proprietaire;
	}

	/** Utilisateur propriétaire des fichiers créés par ce processus (fichier témoin temporaire). */
	private UserPrincipal proprietaireAttendu() throws IOException {
		UserPrincipal attendu = proprietaireAttendu;
		if (attendu == null) {
			Path temoin = Files.createTempFile("plateforme-proprietaire", ".tmp");
			try {
				attendu = Files.getOwner(temoin, LinkOption.NOFOLLOW_LINKS);
			}
			finally {
				Files.deleteIfExists(temoin);
			}
			proprietaireAttendu = attendu;
		}
		return attendu;
	}

	/**
	 * Crée ou contrôle le dossier de travail privé et le renvoie : créé réservé au propriétaire ; existant, il doit être
	 * un vrai dossier (ni lien symbolique, ni jonction) appartenant à l'utilisateur du processus, et ses droits sont
	 * (re)restreints à ce seul utilisateur. Il ne doit être ni le répertoire des plugins ni l'un de ses ancêtres (la
	 * purge y effacerait les jars déposés, les droits restreints s'y appliqueraient). Sinon {@link IOException} : rien
	 * n'est copié, rien n'est chargé, rien n'est purgé.
	 */
	private Path travailPrive() throws IOException {
		Path travail = repertoireTravail();
		Path plugins = repertoire();
		if (plugins.startsWith(travail)) {
			throw new IOException("dossier de travail refusé (égal au répertoire des plugins ou le contient) : "
					+ travail);
		}
		boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
		if (!Files.exists(travail, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(travail)) {
			Path parent = travail.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			if (posix) {
				Files.createDirectory(travail,
						PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
			}
			else {
				Files.createDirectory(travail);
			}
		}
		BasicFileAttributes attributs = Files.readAttributes(travail, BasicFileAttributes.class,
				LinkOption.NOFOLLOW_LINKS);
		if (attributs.isSymbolicLink() || attributs.isOther() || !attributs.isDirectory()) {
			throw new IOException("dossier de travail refusé (lien, jonction ou pas un dossier) : " + travail);
		}
		// Même contrôle sur les chemins réels (répertoire des plugins atteint par un lien, casse...).
		if (Files.exists(plugins) && plugins.toRealPath().startsWith(travail.toRealPath(LinkOption.NOFOLLOW_LINKS))) {
			throw new IOException("dossier de travail refusé (égal au répertoire des plugins ou le contient) : "
					+ travail);
		}
		UserPrincipal proprietaire = Files.getOwner(travail, LinkOption.NOFOLLOW_LINKS);
		if (!proprietaire.equals(proprietaireAttendu())) {
			throw new IOException("dossier de travail refusé (n'appartient pas à l'utilisateur du processus) : "
					+ travail);
		}
		if (posix) {
			Files.setPosixFilePermissions(travail, PosixFilePermissions.fromString("rwx------"));
		}
		else {
			AclFileAttributeView vue = Files.getFileAttributeView(travail, AclFileAttributeView.class,
					LinkOption.NOFOLLOW_LINKS);
			if (vue == null) {
				throw new IOException("dossier de travail : droits non restreignables sur ce système : " + travail);
			}
			List<AclEntry> proprietaireSeul = List.of(AclEntry.newBuilder()
				.setType(AclEntryType.ALLOW)
				.setPrincipal(proprietaire)
				.setPermissions(EnumSet.allOf(AclEntryPermission.class))
				.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
				.build());
			if (!vue.getAcl().equals(proprietaireSeul)) {
				vue.setAcl(proprietaireSeul);
			}
		}
		return travail;
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
