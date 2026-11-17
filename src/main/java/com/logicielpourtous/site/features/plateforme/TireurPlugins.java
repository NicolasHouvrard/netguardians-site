package com.logicielpourtous.site.features.plateforme;

import com.logicielpourtous.site.features.plateforme.ResultatInstallation.Statut;
import com.logicielpourtous.site.sdk.ManifestePlugin;
import com.logicielpourtous.site.sdk.SignatureJar;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tireur de plugins : à intervalle régulier ({@code app.plateforme.intervalle-tirage-ms}, 10 min par défaut), lit le
 * catalogue de la {@link SourcePlugins} (le Maître par HTTP, ou son propre dépôt sur le Maître) et installe, pour
 * chaque plugin, la plus haute version publiée supérieure à l'active et compatible avec la version du cœur
 * ({@code coeurMin}/{@code coeurMax}). Tout ce qui vient de la source est non fiable :
 * <ol>
 * <li>entrées du catalogue validées (nom {@link ManifestePlugin#NOM_VALIDE}, version semver stricte, {@code sha256}
 * hexadécimal, taille au plus {@code app.plateforme.taille-max-jar-mo}, bornes du cœur, pas de doublon contradictoire,
 * au plus {@value #ENTREES_MAX} entrées), le reste est ignoré ;</li>
 * <li>téléchargement dans un dossier privé {@value #DOSSIER_TIRAGE} DU répertoire des plugins (même volume, ignoré
 * par le démarrage qui ne lit que les fichiers {@code .jar} de premier niveau), taille bornée par la source ;</li>
 * <li>taille et {@code sha256} des octets reçus comparés au catalogue, signature vérifiée sur ces octets (si
 * obligatoire), manifeste lu dans le jar vérifié : nom et version doivent être ceux du catalogue ({@code ECHEC}
 * sinon) ;</li>
 * <li>dépôt par déplacement atomique sous un NOUVEAU nom {@code <nom>-<version>.jar} : la {@code .sig} d'abord, le jar
 * ensuite, si bien que le démarrage ne voit jamais un jar sans sa signature (au pire une {@code .sig} orpheline,
 * ignorée, et remplacée au tirage suivant). Un jar déjà présent sous ce nom n'est jamais écrasé : réutilisé s'il a le
 * {@code sha256} du catalogue, sinon {@code ECHEC} ;</li>
 * <li>{@code PluginManager.installer} (qui revérifie la signature sur sa propre copie privée) ; son {@code statut} est
 * rapporté TEL QUEL à la source (un {@code REFUSE} n'est jamais rapporté comme {@code OK}). Un résultat qui nomme un
 * autre plugin ou une autre version que le catalogue devient {@code ECHEC}.</li>
 * </ol>
 * Un refus local (taille, sha256, signature) est rapporté {@code REFUSE} (rien n'est installé ni déposé). Une
 * version refusée ou en échec est mise de côté en mémoire : elle n'est plus retirée tant qu'aucune version plus haute
 * n'apparaît au catalogue. Le jar déposé n'est conservé qu'après {@code OK}, ou après un {@code REFUSE} d'une mise à
 * jour qui ne se fait qu'au redémarrage ({@code miseAJour=coeur}, ou changement de version majeure) : le prochain
 * démarrage le prendra (plus haute version signée). Après tout autre statut, il est retiré : il ferait échouer le
 * prochain démarrage et tomber le plugin.
 * <p>
 * Après chaque tirage réussi, les jars {@code <nom>-<version>.jar} (et leur {@code .sig}) d'un plugin actif plus vieux
 * que la version précédant l'active sont supprimés : on garde l'active, la précédente et toute version plus haute.
 * <p>
 * Source injoignable ou réponse illisible : rien n'est modifié (ni fichiers, ni mises de côté, ni rapports en
 * attente). Un rapport non remis pour une raison passagère est renvoyé au tirage suivant ; un rapport refusé par le
 * Maître (404 : plugin hors catalogue) ne l'est pas. {@link #tirer()} est sérialisé et ne lève jamais (hors erreur de
 * la machine virtuelle).
 */
@Component
@ConditionalOnProperty(name = "app.plateforme.actif", havingValue = "true")
public class TireurPlugins {

	private static final Logger log = LoggerFactory.getLogger(TireurPlugins.class);

	/** Nombre maximal d'entrées d'un catalogue : au-delà, il est jugé absurde et ignoré en entier. */
	static final int ENTREES_MAX = 1000;

	/** Dossier de téléchargement, privé, dans le répertoire des plugins. */
	static final String DOSSIER_TIRAGE = ".tirage";

	private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

	/** Bornes du cœur dans le catalogue : version numérique, sans séparateur de chemin. */
	private static final Pattern VERSION_COEUR = Pattern.compile("^[0-9][0-9A-Za-z.+-]{0,63}$");

	private final SourcePlugins source;

	private final PlateformeProperties proprietes;

	private final ClesPubliques cles;

	private final Function<Path, ResultatInstallation> installateur;

	private final Supplier<Map<String, String>> versionsActives;

	/** Version mise de côté (refusée ou en échec) par plugin. */
	private final Map<String, String> misesDeCote = new HashMap<>();

	/** Dernier rapport non remis par plugin (renvoyé au tirage suivant). */
	private final Map<String, Rapport> enAttente = new LinkedHashMap<>();

	private boolean injoignableSignale;

	private record Rapport(String nom, String version, Statut statut, List<String> raisons) {
	}

	/** Refus local d'une version téléchargée, avec le statut à rapporter. */
	private static final class Refus extends Exception {

		final Statut statut;

		Refus(Statut statut, String raison) {
			super(raison, null, false, false);
			this.statut = statut;
		}

	}

	@Autowired
	public TireurPlugins(SourcePlugins source, PluginManager plugins, PlateformeProperties proprietes,
			ClesPubliques cles) {
		this(source, proprietes, cles, plugins::installer, () -> {
			Map<String, String> versions = new HashMap<>();
			plugins.actifs().forEach((nom, gen) -> versions.put(nom, gen.version));
			return versions;
		});
	}

	/** Pour les tests : installateur et versions actives simulés. */
	TireurPlugins(SourcePlugins source, PlateformeProperties proprietes, ClesPubliques cles,
			Function<Path, ResultatInstallation> installateur, Supplier<Map<String, String>> versionsActives) {
		this.source = source;
		this.proprietes = proprietes;
		this.cles = cles == null ? new ClesPubliques(List.of()) : cles;
		this.installateur = installateur;
		this.versionsActives = versionsActives;
	}

	/** Pour les tests : versions mises de côté. */
	synchronized Map<String, String> misesDeCote() {
		return Map.copyOf(misesDeCote);
	}

	/** Un tirage complet. Sérialisé ; ne lève jamais (une erreur de la machine virtuelle est propagée). */
	@Scheduled(fixedDelayString = "${app.plateforme.intervalle-tirage-ms:600000}",
			initialDelayString = "${app.plateforme.intervalle-tirage-ms:600000}")
	public synchronized void tirer() {
		try {
			tirage();
		}
		catch (VirtualMachineError e) {
			throw e;
		}
		catch (Throwable e) {
			log.error("Tirage des plugins interrompu : {} {}", e.getClass().getSimpleName(),
					DepotPlugins.net(e.getMessage()));
		}
	}

	private void tirage() {
		List<EntreeCatalogue> brut;
		try {
			brut = source.catalogue();
		}
		catch (IOException | RuntimeException e) {
			injoignable(e.getClass().getSimpleName() + " " + DepotPlugins.net(e.getMessage()));
			return;
		}
		if (brut == null) {
			injoignable("catalogue absent");
			return;
		}
		if (brut.size() > ENTREES_MAX) {
			log.warn("Catalogue de plugins ignoré : {} entrées (au plus {})", brut.size(), ENTREES_MAX);
			return;
		}
		if (injoignableSignale) {
			log.info("Source des plugins de nouveau joignable");
			injoignableSignale = false;
		}
		renvoyerRapports();
		List<EntreeCatalogue> choix = choisir(brut, versionsActives.get());
		Path depot = repertoirePlugins();
		if (!choix.isEmpty()) {
			Path tirage;
			try {
				tirage = preparerTirage(depot);
			}
			catch (IOException e) {
				log.error("Tirage des plugins impossible : {}", DepotPlugins.net(e.getMessage()));
				return;
			}
			for (EntreeCatalogue e : choix) {
				traiter(e, depot, tirage);
			}
		}
		nettoyer(depot, versionsActives.get());
	}

	private void injoignable(String cause) {
		if (!injoignableSignale) {
			log.warn("Source des plugins injoignable ou illisible ({}) : rien n'est modifié, nouvel essai au prochain "
					+ "tirage", cause);
			injoignableSignale = true;
		}
		else {
			log.debug("Source des plugins toujours injoignable ({})", cause);
		}
	}

	// --- Choix -----------------------------------------------------------------------------------------------------

	/** Pour chaque plugin : la plus haute version valide, compatible, supérieure à l'active et non mise de côté. */
	private List<EntreeCatalogue> choisir(List<EntreeCatalogue> brut, Map<String, String> actives) {
		Map<String, EntreeCatalogue> valides = new LinkedHashMap<>();
		Set<String> contradictoires = new HashSet<>();
		int ignorees = 0;
		for (EntreeCatalogue e : brut) {
			String raison = invalide(e);
			if (raison != null) {
				ignorees++;
				log.debug("Entrée du catalogue ignorée : {}", raison);
				continue;
			}
			String cle = e.nom() + "-" + e.version();
			EntreeCatalogue deja = valides.putIfAbsent(cle, e);
			if (deja != null && !deja.equals(e)) {
				contradictoires.add(cle);
			}
		}
		contradictoires.forEach(valides::remove);
		if (ignorees > 0 || !contradictoires.isEmpty()) {
			log.warn("Catalogue de plugins : {} entrée(s) invalide(s) et {} version(s) contradictoire(s) ignorée(s)",
					ignorees, contradictoires.size());
		}
		Map<String, EntreeCatalogue> meilleures = new TreeMap<>();
		for (EntreeCatalogue e : valides.values()) {
			String active = actives.get(e.nom());
			if (!compatibleCoeur(e) || (active != null && plusRecente(e.version(), active) <= 0)) {
				continue;
			}
			meilleures.merge(e.nom(), e, (a, b) -> plusRecente(a.version(), b.version()) >= 0 ? a : b);
		}
		List<EntreeCatalogue> choix = new ArrayList<>();
		meilleures.forEach((nom, e) -> {
			String deCote = misesDeCote.get(nom);
			if (deCote != null && plusRecente(e.version(), deCote) <= 0) {
				log.debug("Plugin {} : version {} mise de côté, en attente d'une version plus haute", nom, deCote);
				return;
			}
			choix.add(e);
		});
		return choix;
	}

	/** Raison d'écarter une entrée du catalogue, ou {@code null} si elle est exploitable. */
	private String invalide(EntreeCatalogue e) {
		if (e == null) {
			return "entrée nulle";
		}
		if (e.nom() == null || !ManifestePlugin.NOM_VALIDE.matcher(e.nom()).matches()) {
			return "nom invalide";
		}
		if (!DepotPlugins.versionValide(e.version())) {
			return "version invalide";
		}
		if (!"chaud".equals(e.miseAJour()) && !"coeur".equals(e.miseAJour())) {
			return "miseAJour invalide";
		}
		if (e.coeurMin() == null || !VERSION_COEUR.matcher(e.coeurMin()).matches()
				|| (e.coeurMax() != null && !VERSION_COEUR.matcher(e.coeurMax()).matches())) {
			return "bornes du cœur invalides";
		}
		if (e.sha256() == null || !SHA256.matcher(e.sha256()).matches()) {
			return "sha256 invalide";
		}
		if (e.taille() <= 0 || e.taille() > tailleMax()) {
			return "taille invalide ou au-delà de app.plateforme.taille-max-jar-mo";
		}
		return null;
	}

	private boolean compatibleCoeur(EntreeCatalogue e) {
		try {
			String coeur = proprietes.getVersionCoeur();
			return VerificateurMiseAJour.comparer(coeur, e.coeurMin()) >= 0
					&& (e.coeurMax() == null || VerificateurMiseAJour.comparer(coeur, e.coeurMax()) <= 0);
		}
		catch (RuntimeException ex) {
			return false;
		}
	}

	private static int plusRecente(String a, String b) {
		return VerificateurMiseAJour.comparer(a, b);
	}

	private long tailleMax() {
		return proprietes.getTailleMaxJarMo() * 1024 * 1024;
	}

	// --- Téléchargement, contrôle, dépôt, installation ------------------------------------------------------------

	private void traiter(EntreeCatalogue e, Path depot, Path tirage) {
		String base = e.nom() + "-" + e.version() + ".jar";
		Path jarCible = depot.resolve(base);
		Path sigCible = depot.resolve(base + ".sig");
		try {
			vider(tirage);
			Path jar;
			try {
				jar = source.telecharger(e, tirage);
			}
			catch (IOException | RuntimeException ex) {
				// Passager (réseau, version retirée entre-temps...) : rien n'est mis de côté ni rapporté.
				log.warn("Téléchargement du plugin {} {} impossible : {} {}", e.nom(), e.version(),
						ex.getClass().getSimpleName(), DepotPlugins.net(ex.getMessage()));
				return;
			}
			boolean deposeParLeTireur;
			try {
				verifier(e, tirage, base, jar);
				deposeParLeTireur = deposer(e, base, tirage.resolve(base), jarCible, sigCible);
			}
			catch (Refus refus) {
				log.warn("Plugin {} {} refusé avant installation : {} {}", e.nom(), e.version(), refus.statut,
						refus.getMessage());
				conclure(e, refus.statut, List.of(refus.getMessage()));
				return;
			}
			installer(e, jarCible, sigCible, deposeParLeTireur);
		}
		catch (IOException ex) {
			log.error("Tirage du plugin {} {} interrompu : {}", e.nom(), e.version(), DepotPlugins.net(ex.getMessage()));
		}
		finally {
			try {
				vider(tirage);
			}
			catch (IOException ex) {
				log.warn("Dossier de tirage non vidé : {}", DepotPlugins.net(ex.getMessage()));
			}
		}
	}

	/** Contrôles sur les octets téléchargés : emplacement, taille, sha256, signature, manifeste. */
	private void verifier(EntreeCatalogue e, Path tirage, String base, Path jar) throws Refus {
		Path attendu = tirage.resolve(base);
		if (jar == null || !jar.toAbsolutePath().normalize().equals(attendu)) {
			throw new Refus(Statut.ECHEC, "fichier téléchargé hors du dossier de tirage");
		}
		Path sig = tirage.resolve(base + ".sig");
		if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(sig, LinkOption.NOFOLLOW_LINKS)) {
			throw new Refus(Statut.ECHEC, "jar ou signature téléchargés absents");
		}
		try {
			long taille = Files.size(jar);
			if (taille != e.taille() || taille > tailleMax()) {
				throw new Refus(Statut.REFUSE, "taille différente du catalogue : " + taille + " octets reçus, "
						+ e.taille() + " annoncés");
			}
			if (!sha256(jar).equals(e.sha256())) {
				throw new Refus(Statut.REFUSE, "sha256 différent du catalogue");
			}
			String signature = lireSignature(sig);
			if (proprietes.isSignatureObligatoire()) {
				SignatureJar.Verdict verdict = SignatureJar.verifier(jar, signature, cles.cles());
				if (!verdict.valide()) {
					throw new Refus(Statut.REFUSE, "signature invalide : " + DepotPlugins.net(verdict.raison()));
				}
			}
		}
		catch (IOException ex) {
			throw new Refus(Statut.ECHEC, "jar téléchargé illisible : " + DepotPlugins.net(ex.getMessage()));
		}
		ManifestePlugin m;
		try {
			m = LecteurManifeste.lire(jar);
		}
		catch (RuntimeException ex) {
			throw new Refus(Statut.REFUSE, "manifeste illisible : " + DepotPlugins.net(ex.getMessage()));
		}
		if (!e.nom().equals(m.nom()) || !e.version().equals(m.version())) {
			throw new Refus(Statut.ECHEC, "manifeste différent du catalogue : " + DepotPlugins.net(m.nom()) + " "
					+ DepotPlugins.net(m.version()) + " au lieu de " + e.nom() + " " + e.version());
		}
	}

	private static String lireSignature(Path sig) throws IOException, Refus {
		byte[] octets;
		try (InputStream in = Files.newInputStream(sig, LinkOption.NOFOLLOW_LINKS)) {
			octets = in.readNBytes(PluginManager.TAILLE_MAX_SIG + 1);
		}
		if (octets.length > PluginManager.TAILLE_MAX_SIG) {
			throw new Refus(Statut.REFUSE, "signature illisible : plus de " + PluginManager.TAILLE_MAX_SIG + " octets");
		}
		try {
			return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(octets)).toString();
		}
		catch (CharacterCodingException ex) {
			throw new Refus(Statut.REFUSE, "signature illisible : texte UTF-8 invalide");
		}
	}

	/**
	 * Dépose le jar vérifié sous son nom définitif, sans jamais écraser : {@code .sig} puis jar, par déplacement
	 * atomique sur le même volume. Renvoie {@code true} si le tireur a déposé les fichiers, {@code false} s'il réutilise
	 * un jar identique déjà présent.
	 */
	private boolean deposer(EntreeCatalogue e, String base, Path jar, Path jarCible, Path sigCible)
			throws Refus, IOException {
		Path sig = jar.resolveSibling(base + ".sig");
		if (Files.exists(jarCible, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(jarCible)) {
			if (!Files.isRegularFile(jarCible, LinkOption.NOFOLLOW_LINKS) || !sha256(jarCible).equals(e.sha256())) {
				throw new Refus(Statut.ECHEC,
						"un autre fichier " + base + " est déjà présent dans le répertoire des plugins (jamais écrasé)");
			}
			if (!Files.exists(sigCible, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(sigCible)) {
				Files.move(sig, sigCible, StandardCopyOption.ATOMIC_MOVE);
			}
			log.info("Plugin {} {} : jar identique déjà présent, réutilisé", e.nom(), e.version());
			return false;
		}
		if (Files.exists(sigCible, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(sigCible)) {
			if (!Files.isRegularFile(sigCible, LinkOption.NOFOLLOW_LINKS)) {
				throw new Refus(Statut.ECHEC, base + ".sig existe et n'est pas un fichier ordinaire");
			}
			// Signature orpheline (arrêt entre les deux déplacements d'un tirage précédent) : sans jar, elle ne sert à rien.
			log.warn("Signature orpheline {}.sig remplacée", base);
			Files.delete(sigCible);
		}
		Files.move(sig, sigCible, StandardCopyOption.ATOMIC_MOVE);
		try {
			Files.move(jar, jarCible, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (IOException ex) {
			Files.deleteIfExists(sigCible);
			throw ex;
		}
		return true;
	}

	/**
	 * Mise à jour qui ne peut se faire qu'au redémarrage : {@code miseAJour=coeur} au catalogue, ou changement de
	 * version majeure par rapport à l'active. Seul un tel refus laisse le jar déposé (le prochain démarrage le prend) ;
	 * tout autre refus (dépendance, paquet, migration destructive d'un plugin « chaud »...) échouerait aussi au
	 * démarrage et ferait tomber le plugin : le jar est retiré.
	 */
	private static boolean aFroid(EntreeCatalogue e, String versionActive) {
		if ("coeur".equals(e.miseAJour())) {
			return true;
		}
		return versionActive != null && !majeure(versionActive).equals(majeure(e.version()));
	}

	private static String majeure(String version) {
		int point = version.indexOf('.');
		return (point < 0 ? version : version.substring(0, point)).replaceFirst("^0+(?=\\d)", "");
	}

	private void installer(EntreeCatalogue e, Path jarCible, Path sigCible, boolean deposeParLeTireur) {
		String versionActive = versionsActives.get().get(e.nom());
		ResultatInstallation r;
		try {
			r = installateur.apply(jarCible);
		}
		catch (RuntimeException | LinkageError ex) {
			log.error("Installation du plugin {} {} interrompue", e.nom(), e.version(), ex);
			r = null;
		}
		if (r == null) {
			r = new ResultatInstallation(Statut.ECHEC, null, null, List.of("installation interrompue"), 0);
		}
		Statut statut = r.statut();
		List<String> raisons = new ArrayList<>(r.raisons());
		boolean autre = r.plugin() != null && (!e.nom().equals(r.plugin()) || !e.version().equals(r.version()));
		if (autre) {
			log.error("Plugin {} {} : l'installation a porté sur {} {} ({}) : traité comme ECHEC", e.nom(), e.version(),
					DepotPlugins.net(r.plugin()), DepotPlugins.net(r.version()), statut);
			raisons.add(0, "installation d'un autre plugin que celui du catalogue : " + DepotPlugins.net(r.plugin()) + " "
					+ DepotPlugins.net(r.version()) + " (" + statut + ")");
			statut = Statut.ECHEC;
		}
		boolean garder = statut == Statut.OK
				|| (statut == Statut.REFUSE && r.plugin() != null && !autre && aFroid(e, versionActive));
		if (!garder && deposeParLeTireur) {
			retirer(jarCible, sigCible);
		}
		if (statut == Statut.OK) {
			log.info("Plugin {} {} installé par le tirage", e.nom(), e.version());
		}
		else {
			log.warn("Plugin {} {} non installé par le tirage : {} {}", e.nom(), e.version(), statut,
					raisons.stream().map(DepotPlugins::net).toList());
		}
		conclure(e, statut, raisons);
	}

	/** Mise de côté (tout statut autre que OK) puis rapport. */
	private void conclure(EntreeCatalogue e, Statut statut, List<String> raisons) {
		if (statut == Statut.OK) {
			misesDeCote.remove(e.nom());
		}
		else {
			misesDeCote.put(e.nom(), e.version());
		}
		Rapport rapport = new Rapport(e.nom(), e.version(), statut, List.copyOf(raisons));
		enAttente.remove(e.nom());
		if (!envoyer(rapport)) {
			enAttente.put(e.nom(), rapport);
		}
	}

	private void renvoyerRapports() {
		for (Rapport r : List.copyOf(enAttente.values())) {
			if (envoyer(r)) {
				enAttente.remove(r.nom(), r);
			}
		}
	}

	/** {@code true} si le rapport est remis ou définitivement abandonné ; {@code false} pour un échec passager. */
	private boolean envoyer(Rapport r) {
		try {
			source.rapporter(r.nom(), r.version(), r.statut(), r.raisons());
			return true;
		}
		catch (IOException e) {
			log.warn("Rapport {} {} ({}) non remis : {} ; nouvel essai au prochain tirage", r.nom(), r.version(),
					r.statut(), DepotPlugins.net(e.getMessage()));
			return false;
		}
		catch (RuntimeException e) {
			log.warn("Rapport {} {} ({}) abandonné : {}", r.nom(), r.version(), r.statut(),
					DepotPlugins.net(e.getMessage()));
			return true;
		}
	}

	/** Jar d'abord, signature ensuite : jamais de jar sans sa {@code .sig}. */
	private static void retirer(Path jar, Path sig) {
		try {
			Files.deleteIfExists(jar);
			Files.deleteIfExists(sig);
		}
		catch (IOException e) {
			log.warn("Suppression de {} impossible : {}", jar.getFileName(), DepotPlugins.net(e.getMessage()));
		}
	}

	// --- Dossiers ---------------------------------------------------------------------------------------------------

	private Path repertoirePlugins() {
		return Path.of(proprietes.getRepertoirePlugins()).toAbsolutePath().normalize();
	}

	/** Dossier de téléchargement privé dans le répertoire des plugins (même volume), vidé. */
	private Path preparerTirage(Path depot) throws IOException {
		Files.createDirectories(depot);
		Path tirage = depot.resolve(DOSSIER_TIRAGE);
		DossierPrive.preparer(tirage, DossierPrive.utilisateurDuProcessus(), "dossier de tirage refusé");
		vider(tirage);
		return tirage;
	}

	/** Supprime le contenu direct du dossier de tirage (fichiers et liens, sans les suivre). */
	private static void vider(Path tirage) throws IOException {
		if (!Files.isDirectory(tirage, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try (Stream<Path> fichiers = Files.list(tirage)) {
			for (Path f : fichiers.toList()) {
				if (Files.isDirectory(f, LinkOption.NOFOLLOW_LINKS)) {
					try (Stream<Path> dedans = Files.walk(f)) {
						for (Path p : dedans.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
							Files.deleteIfExists(p);
						}
					}
				}
				else {
					Files.deleteIfExists(f);
				}
			}
		}
	}

	/**
	 * Pour chaque plugin actif : supprime les {@code <nom>-<version>.jar} (et leur {@code .sig}) plus vieux que la
	 * version précédant l'active. Les fichiers d'un autre nom, d'un plugin inactif, et les versions plus hautes que
	 * l'active, ne sont jamais touchés.
	 */
	private void nettoyer(Path depot, Map<String, String> actives) {
		if (actives.isEmpty() || !Files.isDirectory(depot, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		Map<String, Map<String, Path>> parNom = new HashMap<>();
		try (Stream<Path> fichiers = Files.list(depot)) {
			for (Path f : fichiers.toList()) {
				String nomFichier = f.getFileName().toString();
				int tiret = nomFichier.indexOf('-');
				if (!nomFichier.endsWith(".jar") || tiret <= 0 || !Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
					continue;
				}
				String nom = nomFichier.substring(0, tiret);
				String version = nomFichier.substring(tiret + 1, nomFichier.length() - ".jar".length());
				if (actives.containsKey(nom) && ManifestePlugin.NOM_VALIDE.matcher(nom).matches()
						&& DepotPlugins.versionValide(version)) {
					parNom.computeIfAbsent(nom, n -> new HashMap<>()).put(version, f);
				}
			}
		}
		catch (IOException e) {
			log.warn("Nettoyage des anciens jars impossible : {}", DepotPlugins.net(e.getMessage()));
			return;
		}
		parNom.forEach((nom, versions) -> {
			try {
				String active = actives.get(nom);
				String precedente = versions.keySet()
					.stream()
					.filter(v -> plusRecente(v, active) < 0)
					.max(TireurPlugins::plusRecente)
					.orElse(null);
				if (precedente == null) {
					return;
				}
				versions.forEach((version, jar) -> {
					if (plusRecente(version, precedente) < 0) {
						log.info("Ancien jar supprimé : {} (actif {}, précédent {})", jar.getFileName(), active,
								precedente);
						retirer(jar, jar.resolveSibling(jar.getFileName() + ".sig"));
					}
				});
			}
			catch (RuntimeException e) {
				log.warn("Nettoyage du plugin {} ignoré : {}", nom, DepotPlugins.net(e.getMessage()));
			}
		});
	}

	private static String sha256(Path fichier) throws IOException {
		MessageDigest empreinte;
		try {
			empreinte = MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
		try (InputStream in = Files.newInputStream(fichier, LinkOption.NOFOLLOW_LINKS)) {
			byte[] tampon = new byte[64 * 1024];
			int lus;
			while ((lus = in.read(tampon)) != -1) {
				empreinte.update(tampon, 0, lus);
			}
		}
		return HexFormat.of().formatHex(empreinte.digest());
	}

}
