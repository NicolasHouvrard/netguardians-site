package com.logicielpourtous.site.features.plateforme;

import com.logicielpourtous.site.sdk.ManifestePlugin;
import com.logicielpourtous.site.sdk.SignatureJar;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * Dépôt du Maître : versions de plugins publiées ({@code <nom>-<version>.jar} et {@code <nom>-<version>.jar.sig}),
 * servies aux autres serveurs. Le Maître ne signe rien : il n'accepte qu'un jar dont la signature est valide pour une
 * clé de {@link ClesPubliques}, quel que soit {@code app.plateforme.signature-obligatoire}.
 * <p>
 * Publication (jamais « téléverser puis faire confiance ») : la {@code .sig} est lue bornée (16 Kio, UTF-8 strict) ;
 * le jar est copié dans la zone temporaire privée du dépôt en s'arrêtant au-delà de {@code taille-max-jar-mo} (avant
 * toute vérification) ; la signature est vérifiée sur CETTE copie ; le manifeste n'est lu qu'ensuite, dans la copie
 * vérifiée, et lui seul donne le nom et la version (aucun nom de fichier venant du client) ; le {@code sha256} est
 * calculé sur les octets de la copie, qui sont ceux stockés.
 * <p>
 * Installation : sous verrou, si {@code <nom>-<version>.jar} existe déjà, même contenu = publication idempotente,
 * contenu différent = refus. Sinon la {@code .sig} est déplacée atomiquement EN PREMIER, puis le jar : le catalogue
 * étant indexé par les jars, il ne voit jamais un jar sans sa {@code .sig}. Un arrêt brutal entre les deux déplacements
 * ne laisse qu'une {@code .sig} orpheline, invisible, remplacée par la publication suivante de cette version. En plus,
 * le catalogue et {@link #ouvrir} ne présentent que des paires complètes dont la signature se vérifie (résultat mémorisé
 * tant que taille et date des deux fichiers ne changent pas) : un fichier déposé à la main n'y entre pas.
 * <p>
 * Le dépôt est un dossier privé du processus ({@link DossierPrive}), distinct du répertoire des plugins ; sa zone
 * temporaire {@value #TEMPORAIRE} est à l'intérieur (même volume : déplacements atomiques), privée elle aussi et vidée
 * à la première utilisation. Hypothèse : une seule instance du Maître écrit dans ce dépôt (le verrou est en mémoire).
 */
@Component
@ConditionalOnProperty(name = "app.plateforme.actif", havingValue = "true")
@Conditional(SurServeurMaitre.class)
public class DepotPlugins {

	private static final Logger log = LoggerFactory.getLogger(DepotPlugins.class);

	/** Version semver stricte (sans métadonnées {@code +build}) : elle entre telle quelle dans un nom de fichier. */
	static final Pattern VERSION_STRICTE = Pattern
		.compile("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?$");

	static final int LONGUEUR_MAX_VERSION = 64;

	/** Taille maximale d'une {@code .sig} (la vraie fait moins de 200 octets). */
	static final int TAILLE_MAX_SIG = 16 * 1024;

	/** Zone temporaire privée, dans le dépôt. */
	static final String TEMPORAIRE = ".temp";

	private static final int LONGUEUR_MAX_ECHO = 160;

	/** Cause d'un refus de publication (le contrôleur en tire le statut HTTP). */
	public enum Motif {
		/** Signature, manifeste ou version invalides, parties manquantes : 400. */
		INVALIDE,
		/** Au-delà de {@code taille-max-jar-mo} : 413. */
		TROP_VOLUMINEUX,
		/** Même nom et même version déjà publiés avec un autre contenu : 409. */
		CONFLIT
	}

	/** Publication refusée ; le message ne contient que du texte nettoyé (jamais le texte brut reçu). */
	public static final class DepotRefuse extends RuntimeException {

		private final Motif motif;

		DepotRefuse(Motif motif, String message) {
			super(message);
			this.motif = motif;
		}

		public Motif motif() {
			return motif;
		}

	}

	private final PlateformeProperties proprietes;

	private final ClesPubliques cles;

	/** Sérialise l'installation dans le dépôt (contrôle d'existence puis déplacements). */
	private final Object verrou = new Object();

	private final Object verrouTemporaire = new Object();

	private boolean temporairePurge;

	private volatile UserPrincipal proprietaireAttendu;

	/** Vérification mémorisée d'un jar du dépôt ; {@code entree} nulle = paire écartée. */
	private record Memo(long tailleJar, FileTime dateJar, long tailleSig, FileTime dateSig, EntreeCatalogue entree) {
	}

	private final Map<String, Memo> memos = new ConcurrentHashMap<>();

	public DepotPlugins(PlateformeProperties proprietes, ClesPubliques cles) {
		this.proprietes = proprietes;
		this.cles = cles;
	}

	/**
	 * Publie une version : voir la description de la classe.
	 *
	 * @throws DepotRefuse refus (motif et message nettoyé) : rien n'est écrit dans le dépôt
	 * @throws IOException dépôt inutilisable ou écriture impossible : rien n'est publié
	 */
	public EntreeCatalogue publier(InputStream jar, InputStream sig) throws IOException {
		if (jar == null || sig == null) {
			throw refus(Motif.INVALIDE, "jar et signature obligatoires");
		}
		Path depot = depotPrive();
		Path temporaire = temporairePrive(depot);
		byte[] octetsSig = lireSignature(sig);
		String jsonSig = texteUtf8(octetsSig);
		Path copieJar = Files.createTempFile(temporaire, "publication-", ".jar");
		Path copieSig = null;
		try {
			copierBorne(jar, copieJar);
			SignatureJar.Verdict verdict = SignatureJar.verifier(copieJar, jsonSig, cles.cles());
			if (!verdict.valide()) {
				throw refus(Motif.INVALIDE, "signature invalide : " + verdict.raison());
			}
			ManifestePlugin m = manifeste(copieJar);
			String sha256 = sha256(copieJar);
			EntreeCatalogue entree = entree(m, sha256, Files.size(copieJar));
			copieSig = Files.createTempFile(temporaire, "publication-", ".sig");
			Files.write(copieSig, octetsSig, LinkOption.NOFOLLOW_LINKS);
			synchronized (verrou) {
				Path cible = depot.resolve(nomFichier(m.nom(), m.version()));
				Path cibleSig = cible.resolveSibling(cible.getFileName() + ".sig");
				if (Files.exists(cible, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(cible)) {
					if (!Files.isRegularFile(cible, LinkOption.NOFOLLOW_LINKS)) {
						throw refus(Motif.CONFLIT, "emplacement de " + m.nom() + " v" + m.version()
								+ " occupé par autre chose qu'un fichier");
					}
					if (!sha256.equals(sha256(cible))) {
						throw refus(Motif.CONFLIT, "version déjà publiée avec un contenu différent : " + m.nom() + " v"
								+ m.version() + " (publier une nouvelle version)");
					}
					// Même contenu : rien à faire, sauf réparer une .sig absente ou qui ne vérifie plus ce jar
					// (la nouvelle vient d'être vérifiée sur des octets identiques).
					if (!sigValide(cible, cibleSig)) {
						Files.move(copieSig, cibleSig, StandardCopyOption.ATOMIC_MOVE);
					}
					log.info("Dépôt : {} v{} déjà publié avec le même contenu (sha256 {}) : publication idempotente",
							m.nom(), m.version(), sha256);
					return entree;
				}
				// .sig d'abord, jar ensuite : jamais de jar sans sa .sig (une .sig orpheline éventuelle est remplacée).
				Files.move(copieSig, cibleSig, StandardCopyOption.ATOMIC_MOVE);
				Files.move(copieJar, cible, StandardCopyOption.ATOMIC_MOVE);
			}
			log.info("Dépôt : {} v{} publié (sha256 {}, {} octets, clé {})", m.nom(), m.version(), sha256,
					entree.taille(), verdict.keyId());
			return entree;
		}
		catch (DepotRefuse e) {
			log.warn("Dépôt : publication refusée : {}", e.getMessage());
			throw e;
		}
		finally {
			Files.deleteIfExists(copieJar);
			if (copieSig != null) {
				Files.deleteIfExists(copieSig);
			}
		}
	}

	/**
	 * Versions publiées, triées par nom puis par version (ordre semver). Seules les paires complètes dont la signature
	 * se vérifie et dont le nom de fichier correspond au manifeste sont présentées.
	 */
	public List<EntreeCatalogue> catalogue() {
		Path depot = repertoireDepot();
		if (!Files.exists(depot, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(depot)) {
			return List.of();
		}
		List<EntreeCatalogue> entrees = new ArrayList<>();
		Set<String> vus = new HashSet<>();
		try {
			depotPrive();
			try (Stream<Path> fichiers = Files.list(depot)) {
				for (Path f : fichiers.toList()) {
					String nom = f.getFileName().toString();
					if (!nom.endsWith(".jar") || !Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
						continue;
					}
					vus.add(nom);
					EntreeCatalogue e = entreeVerifiee(f);
					if (e != null) {
						entrees.add(e);
					}
				}
			}
		}
		catch (IOException e) {
			log.error("Dépôt : catalogue illisible : {}", e.getMessage());
			return List.of();
		}
		memos.keySet().retainAll(vus);
		entrees.sort(Comparator.comparing(EntreeCatalogue::nom)
			.thenComparing(EntreeCatalogue::version, VerificateurMiseAJour::comparer));
		return entrees;
	}

	/**
	 * Fichier publié {@code <nom>-<version>.jar} ({@code extension = "jar"}) ou sa signature {@code .jar.sig}
	 * ({@code extension = "sig"}). Le nom est validé par {@link ManifestePlugin#NOM_VALIDE}, la version par un motif
	 * semver strict : aucun séparateur, aucun {@code ..}, donc aucune traversée de chemin. Seule une paire complète et
	 * vérifiée, faite de fichiers ordinaires (pas de lien), est ouverte.
	 *
	 * @throws IllegalArgumentException nom, version ou extension invalides
	 * @throws NoSuchFileException version absente du catalogue
	 */
	public Path ouvrir(String nom, String version, String extension) throws IOException {
		if (nom == null || !ManifestePlugin.NOM_VALIDE.matcher(nom).matches()) {
			throw new IllegalArgumentException("nom de plugin invalide");
		}
		if (!versionValide(version)) {
			throw new IllegalArgumentException("version invalide (semver strict attendu)");
		}
		if (extension == null || !(extension.equals("jar") || extension.equals("sig"))) {
			throw new IllegalArgumentException("extension invalide (jar ou sig)");
		}
		Path depot = repertoireDepot();
		String fichierJar = nomFichier(nom, version);
		if (!Files.exists(depot, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(depot)) {
			throw new NoSuchFileException(fichierJar);
		}
		depotPrive();
		Path jar = depot.resolve(fichierJar);
		if (!depot.equals(jar.getParent()) || !Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)
				|| entreeVerifiee(jar) == null) {
			throw new NoSuchFileException(fichierJar);
		}
		return extension.equals("jar") ? jar : jar.resolveSibling(fichierJar + ".sig");
	}

	/** Pour les tests : simule un dépôt appartenant à un autre utilisateur. */
	void proprietaireAttendu(UserPrincipal proprietaire) {
		this.proprietaireAttendu = proprietaire;
	}

	// ---- Dossiers ----

	Path repertoireDepot() {
		return Path.of(proprietes.getDepotMaitre()).toAbsolutePath().normalize();
	}

	/** Dépôt contrôlé (privé, pas un lien, distinct du répertoire des plugins), créé au besoin. */
	private Path depotPrive() throws IOException {
		Path depot = repertoireDepot();
		Path plugins = Path.of(proprietes.getRepertoirePlugins()).toAbsolutePath().normalize();
		if (plugins.startsWith(depot) || depot.startsWith(plugins)) {
			throw new IOException("dépôt refusé (confondu avec le répertoire des plugins) : " + depot);
		}
		DossierPrive.preparer(depot, proprietaireAttendu(), "dépôt refusé");
		if (Files.exists(plugins)) {
			Path reelPlugins = plugins.toRealPath();
			Path reelDepot = depot.toRealPath(LinkOption.NOFOLLOW_LINKS);
			if (reelPlugins.startsWith(reelDepot) || reelDepot.startsWith(reelPlugins)) {
				throw new IOException("dépôt refusé (confondu avec le répertoire des plugins) : " + depot);
			}
		}
		return depot;
	}

	/** Zone temporaire privée du dépôt ; vidée (fichiers ordinaires seulement) à la première utilisation. */
	private Path temporairePrive(Path depot) throws IOException {
		synchronized (verrouTemporaire) {
			Path temporaire = DossierPrive.preparer(depot.resolve(TEMPORAIRE), proprietaireAttendu(),
					"zone temporaire du dépôt refusée");
			if (!temporairePurge) {
				try (Stream<Path> fichiers = Files.list(temporaire)) {
					for (Path f : fichiers.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
						Files.deleteIfExists(f);
					}
				}
				temporairePurge = true;
			}
			return temporaire;
		}
	}

	private UserPrincipal proprietaireAttendu() throws IOException {
		UserPrincipal attendu = proprietaireAttendu;
		if (attendu == null) {
			attendu = DossierPrive.utilisateurDuProcessus();
			proprietaireAttendu = attendu;
		}
		return attendu;
	}

	// ---- Lecture et vérification ----

	private static byte[] lireSignature(InputStream sig) throws IOException {
		byte[] octets = sig.readNBytes(TAILLE_MAX_SIG + 1);
		if (octets.length > TAILLE_MAX_SIG) {
			throw refus(Motif.INVALIDE, "signature trop volumineuse : plus de " + TAILLE_MAX_SIG + " octets");
		}
		return octets;
	}

	private static String texteUtf8(byte[] octets) {
		try {
			return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(octets)).toString();
		}
		catch (CharacterCodingException e) {
			throw refus(Motif.INVALIDE, "signature illisible : texte UTF-8 invalide");
		}
	}

	/** Copie en s'arrêtant dès que {@code taille-max-jar-mo} est dépassée (octets réellement lus). */
	private void copierBorne(InputStream source, Path copie) throws IOException {
		long max = proprietes.getTailleMaxJarMo() * 1024 * 1024;
		try (OutputStream out = Files.newOutputStream(copie, LinkOption.NOFOLLOW_LINKS)) {
			byte[] tampon = new byte[64 * 1024];
			long total = 0;
			int lus;
			while ((lus = source.read(tampon)) != -1) {
				total += lus;
				if (total > max) {
					throw refus(Motif.TROP_VOLUMINEUX, "jar trop volumineux : plus de " + proprietes.getTailleMaxJarMo()
							+ " Mo (app.plateforme.taille-max-jar-mo)");
				}
				out.write(tampon, 0, lus);
			}
		}
	}

	/** Manifeste de la copie VÉRIFIÉE ; nom validé par le manifeste, version par le motif strict. */
	private static ManifestePlugin manifeste(Path copieVerifiee) {
		ManifestePlugin m;
		try {
			m = LecteurManifeste.lire(copieVerifiee);
		}
		catch (IllegalArgumentException e) {
			throw refus(Motif.INVALIDE, "manifeste invalide : " + e.getMessage());
		}
		if (!versionValide(m.version())) {
			throw refus(Motif.INVALIDE, "version invalide dans le manifeste (semver strict attendu) : " + m.version());
		}
		return m;
	}

	/**
	 * Entrée d'un jar du dépôt si la paire est complète, vérifiée et nommée d'après son manifeste ; sinon {@code null}
	 * (avertissement). Mémorisée tant que taille et date des deux fichiers ne changent pas.
	 */
	private EntreeCatalogue entreeVerifiee(Path jar) {
		String nomFichier = jar.getFileName().toString();
		Path sig = jar.resolveSibling(nomFichier + ".sig");
		try {
			if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(sig, LinkOption.NOFOLLOW_LINKS)) {
				return null;
			}
			BasicFileAttributes aJar = Files.readAttributes(jar, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			BasicFileAttributes aSig = Files.readAttributes(sig, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			Memo memo = memos.get(nomFichier);
			if (memo != null && memo.tailleJar() == aJar.size() && memo.dateJar().equals(aJar.lastModifiedTime())
					&& memo.tailleSig() == aSig.size() && memo.dateSig().equals(aSig.lastModifiedTime())) {
				return memo.entree();
			}
			EntreeCatalogue entree = null;
			String raison = null;
			if (!sigValide(jar, sig)) {
				raison = "signature absente ou invalide";
			}
			else {
				try {
					ManifestePlugin m = manifeste(jar);
					if (!nomFichier.equals(nomFichier(m.nom(), m.version()))) {
						raison = "nom de fichier différent du manifeste";
					}
					else {
						entree = entree(m, sha256(jar), Files.size(jar));
					}
				}
				catch (DepotRefuse e) {
					raison = e.getMessage();
				}
			}
			if (entree == null) {
				log.warn("Dépôt : {} écarté du catalogue : {}", net(nomFichier), raison);
			}
			memos.put(nomFichier, new Memo(aJar.size(), aJar.lastModifiedTime(), aSig.size(), aSig.lastModifiedTime(),
					entree));
			return entree;
		}
		catch (IOException e) {
			log.warn("Dépôt : {} illisible : {}", net(nomFichier), e.getMessage());
			return null;
		}
	}

	/** {@code sig} est un fichier ordinaire, borné, UTF-8, dont la signature vérifie {@code jar}. */
	private boolean sigValide(Path jar, Path sig) throws IOException {
		if (!Files.isRegularFile(sig, LinkOption.NOFOLLOW_LINKS)) {
			return false;
		}
		try (InputStream in = Files.newInputStream(sig, LinkOption.NOFOLLOW_LINKS)) {
			return SignatureJar.verifier(jar, texteUtf8(lireSignature(in)), cles.cles()).valide();
		}
		catch (DepotRefuse e) {
			return false;
		}
	}

	// ---- Utilitaires ----

	private static EntreeCatalogue entree(ManifestePlugin m, String sha256, long taille) {
		return new EntreeCatalogue(m.nom(), m.version(), m.miseAJour().name().toLowerCase(Locale.ROOT), m.coeurMin(),
				m.coeurMax(), sha256, taille);
	}

	static boolean versionValide(String version) {
		return version != null && version.length() <= LONGUEUR_MAX_VERSION && VERSION_STRICTE.matcher(version).matches();
	}

	private static String nomFichier(String nom, String version) {
		return nom + "-" + version + ".jar";
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

	private static DepotRefuse refus(Motif motif, String message) {
		return new DepotRefuse(motif, net(message));
	}

	/**
	 * Texte pouvant venir de l'extérieur, nettoyé avant d'entrer dans un message ou un journal (même approche que
	 * {@code SignatureJar}) : liste blanche ASCII imprimable plus lettres latines accentuées (U+00C0 à U+00FF), le
	 * reste (contrôles, bidi, substituts...) et les caractères de balisage {@code < > & " ` \} deviennent {@code ?} ;
	 * tronqué. (L'apostrophe reste : elle sert au français et n'ouvre rien en JSON.)
	 */
	static String net(String texte) {
		if (texte == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < texte.length() && sb.length() < LONGUEUR_MAX_ECHO; i++) {
			char c = texte.charAt(i);
			boolean admis = ((c >= 0x20 && c <= 0x7e) || (c >= 0xc0 && c <= 0xff)) && "<>&\"`\\".indexOf(c) < 0;
			sb.append(admis ? c : '?');
		}
		return texte.length() > LONGUEUR_MAX_ECHO ? sb + "..." : sb.toString();
	}

}
