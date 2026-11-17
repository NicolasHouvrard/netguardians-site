package com.logicielpourtous.site.features.plateforme;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.logicielpourtous.site.features.commun.CleMaitre;
import com.logicielpourtous.site.sdk.ManifestePlugin;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Source du tireur sur un serveur PARTNER/CLIENT : le Maître, par HTTP, routes {@code /api/licence/plugins/**}
 * authentifiées par l'en-tête {@code X-Licence-Api-Key} (clé fournie par {@link CleMaitre}, lue à chaque appel ;
 * jamais journalisée ni placée dans un message d'erreur).
 * <p>
 * Client volontairement minimal et borné ({@link HttpURLConnection}, confiance TLS par défaut du JDK comme
 * {@code LicenceClient}) : connexion {@value #SECONDES_CONNEXION} s, lecture {@value #SECONDES_LECTURE} s (délai
 * entre deux paquets reçus) et durée totale d'un appel au plus {@value #MINUTES_APPEL_MAX} min (un corps au
 * goutte-à-goutte est coupé) ; aucune redirection suivie (une réponse 3xx est une erreur) ; {@code Accept: *}{@code /*}
 * et jamais de {@code Range} (le Maître ne gère pas les plages) ; corps bornés (catalogue
 * {@value #TAILLE_MAX_CATALOGUE} octets, jar {@code taille} de l'entrée du catalogue, signature
 * {@value #TAILLE_MAX_SIG} octets ; au-delà : {@link TelechargementRefuse}) ; le rapport d'état reste sous la borne du
 * Maître ; seuls 404 (plugin hors catalogue), 401 et 403 sont des refus définitifs du rapport, tout autre échec est
 * retenté au tirage suivant. La clé n'est envoyée que faite de caractères ASCII visibles.
 */
public class SourceMaitreHttp implements SourcePlugins {

	private static final Logger log = LoggerFactory.getLogger(SourceMaitreHttp.class);

	static final long SECONDES_CONNEXION = 5;

	static final long SECONDES_LECTURE = 60;

	static final long MINUTES_APPEL_MAX = 15;

	static final Duration DELAI_CONNEXION = Duration.ofSeconds(SECONDES_CONNEXION);

	static final Duration DELAI_LECTURE = Duration.ofSeconds(SECONDES_LECTURE);

	/** Taille maximale de la réponse du catalogue. */
	static final int TAILLE_MAX_CATALOGUE = 1024 * 1024;

	/** Taille maximale d'une signature (comme {@code PluginManager.TAILLE_MAX_SIG}). */
	static final int TAILLE_MAX_SIG = PluginManager.TAILLE_MAX_SIG;

	/** Raisons envoyées au plus dans un rapport, et longueur de chacune (le Maître en garde 20, tronquées à 160). */
	static final int RAISONS_MAX = 20;

	static final int LONGUEUR_MAX_RAISON = 160;

	static final String ENTETE_CLE = "X-Licence-Api-Key";

	static final String PREFIXE = "/api/licence/plugins/";

	private static final ObjectMapper JSON = JsonMapper.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.build();

	private final String urlMaitre;

	private final Supplier<Optional<String>> cle;

	private final Duration connexion;

	private final Duration lecture;

	private final Duration dureeMax;

	/** Mesure du Metaspace local (peut renvoyer {@code null} : rien n'est joint). */
	private final Supplier<SurveillanceMetaspace.Mesure> mesure;

	/**
	 * @param urlMaitre URL de base du Maître ({@code http(s)://hôte[:port]}), en pratique {@code app.licence.master-url}
	 * @param cle clé API de licence de cette installation
	 */
	public SourceMaitreHttp(String urlMaitre, CleMaitre cle) {
		this(urlMaitre, cle::cle, DELAI_CONNEXION, DELAI_LECTURE);
	}

	/** Avec la mesure du Metaspace local, jointe à chaque rapport d'état (le Maître l'affiche, spec §7). */
	public SourceMaitreHttp(String urlMaitre, CleMaitre cle, Supplier<SurveillanceMetaspace.Mesure> mesure) {
		this(urlMaitre, cle::cle, DELAI_CONNEXION, DELAI_LECTURE, mesure);
	}

	/** Pour les tests : délais réduits. */
	SourceMaitreHttp(String urlMaitre, Supplier<Optional<String>> cle, Duration connexion, Duration lecture) {
		this(urlMaitre, cle, connexion, lecture, () -> null);
	}

	SourceMaitreHttp(String urlMaitre, Supplier<Optional<String>> cle, Duration connexion, Duration lecture,
			Supplier<SurveillanceMetaspace.Mesure> mesure) {
		this.mesure = mesure;
		String url = urlMaitre == null ? "" : urlMaitre.trim();
		while (url.endsWith("/")) {
			url = url.substring(0, url.length() - 1);
		}
		this.urlMaitre = url;
		this.cle = cle;
		this.connexion = connexion;
		this.lecture = lecture;
		this.dureeMax = Duration.ofMinutes(MINUTES_APPEL_MAX);
	}

	/** URL de base retenue (sans barre finale). */
	String urlMaitre() {
		return urlMaitre;
	}

	Duration delaiConnexion() {
		return connexion;
	}

	Duration delaiLecture() {
		return lecture;
	}

	@Override
	public List<EntreeCatalogue> catalogue() throws IOException {
		HttpURLConnection c = ouvrir("GET", "catalogue");
		try {
			exigerSucces(c, "catalogue");
			byte[] corps = lireBorne(c, TAILLE_MAX_CATALOGUE, "catalogue");
			JsonNode racine;
			try {
				racine = JSON.readTree(corps);
			}
			catch (IOException e) {
				throw new IOException("catalogue du Maître illisible (JSON invalide)");
			}
			if (racine == null || !racine.isArray()) {
				throw new IOException("catalogue du Maître illisible (tableau attendu)");
			}
			List<EntreeCatalogue> entrees = new ArrayList<>();
			int ignores = 0;
			for (JsonNode n : racine) {
				EntreeCatalogue e = entree(n);
				if (e == null) {
					ignores++;
				}
				else {
					entrees.add(e);
				}
			}
			if (ignores > 0) {
				log.warn("Catalogue du Maître : {} élément(s) mal formé(s) ignoré(s)", ignores);
			}
			return entrees;
		}
		finally {
			c.disconnect();
		}
	}

	/** Élément du catalogue aux types attendus, ou {@code null} (le tireur valide ensuite le contenu). */
	private static EntreeCatalogue entree(JsonNode n) {
		if (n == null || !n.isObject()) {
			return null;
		}
		JsonNode taille = n.get("taille");
		if (taille == null || !taille.isIntegralNumber() || !taille.canConvertToLong()) {
			return null;
		}
		String[] textes = new String[6];
		String[] champs = { "nom", "version", "miseAJour", "coeurMin", "coeurMax", "sha256" };
		for (int i = 0; i < champs.length; i++) {
			JsonNode v = n.get(champs[i]);
			if (v == null || v.isNull()) {
				textes[i] = null;
			}
			else if (v.isTextual()) {
				textes[i] = v.textValue();
			}
			else {
				return null;
			}
		}
		return new EntreeCatalogue(textes[0], textes[1], textes[2], textes[3], textes[4], textes[5],
				taille.longValue());
	}

	@Override
	public Path telecharger(EntreeCatalogue e, Path dossier) throws IOException {
		if (e == null || e.nom() == null || !ManifestePlugin.NOM_VALIDE.matcher(e.nom()).matches()
				|| !DepotPlugins.versionValide(e.version())) {
			throw new IOException("entrée du catalogue invalide (nom ou version)");
		}
		if (e.taille() <= 0 || e.taille() > PlateformeProperties.TAILLE_MAX_JAR_MO_MAX * 1024 * 1024) {
			throw new IOException("entrée du catalogue invalide (taille)");
		}
		String base = e.nom() + "-" + e.version() + ".jar";
		Path jar = dossier.resolve(base);
		Path sig = dossier.resolve(base + ".sig");
		String chemin = e.nom() + "/" + e.version() + "/";
		boolean fini = false;
		try {
			copier(chemin + "jar", jar, e.taille());
			copier(chemin + "sig", sig, TAILLE_MAX_SIG);
			fini = true;
			return jar;
		}
		finally {
			if (!fini) {
				Files.deleteIfExists(jar);
				Files.deleteIfExists(sig);
			}
		}
	}

	/** Télécharge {@code PREFIXE + chemin} dans {@code cible} (créé, jamais écrasé), au plus {@code max} octets. */
	private void copier(String chemin, Path cible, long max) throws IOException {
		HttpURLConnection c = ouvrir("GET", chemin);
		try {
			if (c.getResponseCode() == HttpURLConnection.HTTP_NOT_FOUND) {
				throw new NoSuchFileException("version absente du dépôt du Maître : " + chemin);
			}
			exigerSucces(c, chemin);
			long annonce = c.getContentLengthLong();
			if (annonce > max) {
				throw new TelechargementRefuse(chemin + " : taille annoncée " + annonce + " > " + max + " octets");
			}
			long debut = System.nanoTime();
			try (InputStream in = c.getInputStream();
					OutputStream out = Files.newOutputStream(cible, StandardOpenOption.CREATE_NEW,
							StandardOpenOption.WRITE)) {
				byte[] tampon = new byte[64 * 1024];
				long total = 0;
				int lus;
				while ((lus = in.read(tampon)) != -1) {
					total += lus;
					if (total > max) {
						throw new TelechargementRefuse(chemin + " : plus de " + max + " octets reçus");
					}
					out.write(tampon, 0, lus);
					verifierDuree(debut, chemin);
				}
			}
		}
		finally {
			c.disconnect();
		}
	}

	@Override
	public void rapporter(String nom, String version, ResultatInstallation.Statut statut, List<String> raisons)
			throws IOException {
		SurveillanceMetaspace.Mesure m = null;
		try {
			m = mesure.get();
		}
		catch (RuntimeException e) {
			// La mesure est accessoire : son échec ne doit jamais empêcher le rapport d'état.
			log.warn("Mesure du Metaspace indisponible pour le rapport ({})", e.getClass().getSimpleName());
		}
		byte[] corps = corpsRapport(nom, version, statut, raisons, m);
		HttpURLConnection c = ouvrir("POST", "etat");
		try {
			c.setDoOutput(true);
			c.setRequestProperty("Content-Type", "application/json");
			c.setFixedLengthStreamingMode(corps.length);
			try (OutputStream out = c.getOutputStream()) {
				out.write(corps);
			}
			int code = c.getResponseCode();
			if (code >= 200 && code < 300) {
				return;
			}
			if (code == HttpURLConnection.HTTP_NOT_FOUND) {
				// Plugin absent du catalogue du Maître : rien à suivre là-bas, on n'insiste pas.
				log.info("Rapport {} {} ({}) ignoré par le Maître : plugin absent de son catalogue", nom, version,
						statut);
				return;
			}
			if (code == HttpURLConnection.HTTP_UNAUTHORIZED || code == HttpURLConnection.HTTP_FORBIDDEN) {
				log.warn("Rapport {} {} ({}) refusé par le Maître : HTTP {} (clé refusée ; pas de nouvelle tentative)",
						nom, version, statut, code);
				return;
			}
			throw new IOException("rapport d'état : HTTP " + code);
		}
		finally {
			c.disconnect();
		}
	}

	/** JSON {@code {nom, version, statut, raisons}} sous la borne du Maître (raisons bornées en nombre et longueur). */
	static byte[] corpsRapport(String nom, String version, ResultatInstallation.Statut statut, List<String> raisons)
			throws IOException {
		return corpsRapport(nom, version, statut, raisons, null);
	}

	/** Idem, avec la mesure du Metaspace ({@code metaspace}) si elle est fournie. */
	static byte[] corpsRapport(String nom, String version, ResultatInstallation.Statut statut, List<String> raisons,
			SurveillanceMetaspace.Mesure metaspace) throws IOException {
		List<String> retenues = new ArrayList<>();
		for (String r : raisons == null ? List.<String>of() : raisons) {
			if (retenues.size() >= RAISONS_MAX) {
				break;
			}
			String t = r == null ? "" : r;
			retenues.add(t.length() > LONGUEUR_MAX_RAISON ? t.substring(0, LONGUEUR_MAX_RAISON) : t);
		}
		while (true) {
			ObjectNode o = JSON.createObjectNode();
			o.put("nom", nom);
			o.put("version", version);
			o.put("statut", statut.name());
			ArrayNode a = o.putArray("raisons");
			retenues.forEach(a::add);
			if (metaspace != null) {
				ObjectNode m = o.putObject("metaspace");
				m.put("metaspaceMo", metaspace.metaspaceMo());
				m.put("seuilMo", metaspace.seuilMo());
				m.put("redemarrageRecommande", metaspace.redemarrageRecommande());
			}
			byte[] corps = JSON.writeValueAsBytes(o);
			if (corps.length < DistributionController.TAILLE_MAX_RAPPORT - 1024 || retenues.isEmpty()) {
				return corps;
			}
			retenues.remove(retenues.size() - 1);
		}
	}

	/**
	 * Connexion configurée vers {@code PREFIXE + chemin} : URL du Maître http(s) seulement, clé présente, délais
	 * bornés, aucune redirection, {@code Accept: *}{@code /*}. Aucun message ne contient la clé.
	 */
	private HttpURLConnection ouvrir(String methode, String chemin) throws IOException {
		if (urlMaitre.isEmpty()) {
			throw new IOException("URL du Maître non configurée (app.licence.master-url)");
		}
		URI uri;
		try {
			URI racine = new URI(urlMaitre);
			String schema = racine.getScheme() == null ? "" : racine.getScheme().toLowerCase(Locale.ROOT);
			if (!(schema.equals("http") || schema.equals("https")) || racine.getHost() == null
					|| racine.getRawUserInfo() != null || racine.getRawQuery() != null
					|| racine.getRawFragment() != null) {
				throw new IOException("URL du Maître invalide (http ou https avec un hôte attendu)");
			}
			uri = new URI(urlMaitre + PREFIXE + chemin);
		}
		catch (URISyntaxException e) {
			throw new IOException("URL du Maître invalide");
		}
		String valeur = cle.get().filter(k -> !k.isBlank()).orElse(null);
		if (valeur == null) {
			throw new IOException("installation non enrôlée auprès du Maître : aucune clé API de licence");
		}
		// Jamais de CR/LF, d'espace ni de contrôle dans un en-tête : le JDK citerait sinon la valeur dans son message.
		for (int i = 0; i < valeur.length(); i++) {
			char ch = valeur.charAt(i);
			if (ch < 0x21 || ch > 0x7e) {
				throw new IOException("clé API de licence invalide (caractère interdit) : rien n'est envoyé");
			}
		}
		HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
		c.setConnectTimeout((int) connexion.toMillis());
		c.setReadTimeout((int) lecture.toMillis());
		c.setInstanceFollowRedirects(false);
		c.setUseCaches(false);
		c.setRequestMethod(methode);
		c.setRequestProperty("Accept", "*/*");
		c.setRequestProperty(ENTETE_CLE, valeur);
		return c;
	}

	private static void exigerSucces(HttpURLConnection c, String chemin) throws IOException {
		int code = c.getResponseCode();
		if (code >= 300 && code < 400) {
			throw new IOException(chemin + " : redirection refusée (HTTP " + code + ")");
		}
		if (code != HttpURLConnection.HTTP_OK) {
			throw new IOException(chemin + " : HTTP " + code);
		}
	}

	private byte[] lireBorne(HttpURLConnection c, int max, String chemin) throws IOException {
		long debut = System.nanoTime();
		try (InputStream in = c.getInputStream()) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] tampon = new byte[16 * 1024];
			int lus;
			while ((lus = in.read(tampon)) != -1) {
				if (out.size() + lus > max) {
					throw new IOException(chemin + " : réponse de plus de " + max + " octets");
				}
				out.write(tampon, 0, lus);
				verifierDuree(debut, chemin);
			}
			return out.toByteArray();
		}
	}

	private void verifierDuree(long debut, String chemin) throws IOException {
		if (System.nanoTime() - debut > dureeMax.toNanos()) {
			throw new IOException(chemin + " : durée maximale de " + dureeMax.toMinutes() + " min dépassée");
		}
	}

}
