package com.logicielpourtous.site.features.plateforme;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.Limit;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.logicielpourtous.site.sdk.ManifestePlugin;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Distribution des plugins du Maître aux serveurs et état qu'ils rapportent. N'existe que sur un Maître
 * ({@code app.plateforme.actif=true} et {@code app.role=MASTER}, comme {@link DepotPlugins}).
 * <p>
 * Routes serveur → Maître, montées sur {@code SecurityConfig.licenceApiFilterChain} (clé {@code X-Licence-Api-Key},
 * chacune déclarée explicitement avec {@code hasRole(LicenceApiKeyAuthFilter.ROLE)}) ; le principal posé par ce filtre
 * est l'id ({@code Long}) du {@code LicenceServeur} appelant, seule identité retenue (jamais le corps) :
 * <ul>
 * <li>{@code GET /api/licence/plugins/catalogue} : {@link DepotPlugins#catalogue()} ;</li>
 * <li>{@code GET /api/licence/plugins/{nom}/{version}/jar} et {@code /sig} : octets stockés d'une paire complète et
 * vérifiée ({@link DepotPlugins#ouvrir}), ouverts sans suivre de lien, en flux avec {@code Content-Length} ; 400 nom
 * ou version invalides, 404 version absente ;</li>
 * <li>{@code POST /api/licence/plugins/etat} {@code {nom, version, statut, raisons[]}} : 204 ; remplace le dernier
 * état du même serveur pour ce plugin. {@code statut} est un nom exact de {@link ResultatInstallation.Statut}
 * ({@code OK}, {@code REFUSE}, {@code ECHEC}, {@code ANNULE}) ; corps borné à {@value #TAILLE_MAX_RAPPORT} octets
 * (413 au-delà) ; au plus {@value #RAISONS_MAX} raisons conservées, chacune nettoyée et tronquée, les raisons vides
 * ou blanches écartées. {@code nom} doit figurer au catalogue du dépôt (404 sinon, rien d'écrit) ; au plus
 * {@code etatsMaxParServeur} (défaut {@value #ETATS_MAX_PAR_SERVEUR}) lignes par serveur : un nouveau nom au-delà
 * est refusé (409), la mise à jour d'un nom déjà présent reste admise.</li>
 * </ul>
 * {@code GET /api/plateforme/serveurs-etat?limite=n} (chaîne {@code /api/plateforme/**}, ADMIN ; garde de méthode en
 * plus) : derniers états, triés par serveur puis par plugin, au plus {@code n} (défaut {@value #LISTE_DEFAUT}, ramené
 * à {@value #LISTE_MAX} ; 400 si {@code n < 1} ou non entier).
 * <p>
 * Toutes les réponses d'erreur ont un {@code Content-Type} JSON fixé : un {@code Accept} qui exclut JSON (ex.
 * {@code application/octet-stream} pour un jar) ne transforme pas un 400/404 en 500. Les plages ({@code Range}) ne sont
 * pas prises en charge sur jar/sig : le client télécharge le fichier entier.
 */
@RestController
@ConditionalOnProperty(name = "app.plateforme.actif", havingValue = "true")
@Conditional(SurServeurMaitre.class)
public class DistributionController {

	private static final Logger log = LoggerFactory.getLogger(DistributionController.class);

	/** Taille maximale du corps d'un rapport d'état (octets). */
	static final int TAILLE_MAX_RAPPORT = 16 * 1024;

	/** Nombre maximal de raisons conservées par rapport. */
	static final int RAISONS_MAX = 20;

	/** Nombre de lignes renvoyées par défaut par {@code serveurs-etat}. */
	static final int LISTE_DEFAUT = 500;

	/** Nombre maximal de lignes renvoyées par {@code serveurs-etat}. */
	static final int LISTE_MAX = 2000;

	/** Nombre maximal de plugins distincts suivis par serveur. */
	static final int ETATS_MAX_PAR_SERVEUR = 200;

	private volatile int etatsMaxParServeur = ETATS_MAX_PAR_SERVEUR;

	/** Pour les tests : plafond courant de lignes par serveur. */
	int etatsMaxParServeur() {
		return etatsMaxParServeur;
	}

	/** Pour les tests : abaisse le plafond de lignes par serveur. */
	void etatsMaxParServeur(int max) {
		this.etatsMaxParServeur = max;
	}

	private static final ObjectMapper JSON = JsonMapper.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
		.build();

	private static final MediaType JAR = MediaType.parseMediaType("application/java-archive");

	/** Vue d'un état rapporté. */
	public record EtatServeur(long licenceServeurId, String nom, String version, String statut, List<String> raisons,
			Instant rapporteLe, MetaspaceServeur metaspace) {
	}

	/** Mesure du Metaspace rapportée par le serveur (spec §7), {@code null} dans la vue si absente ou invalide. */
	public record MetaspaceServeur(long metaspaceMo, long seuilMo, boolean redemarrageRecommande) {
	}

	/** Borne haute (Mo) d'une mesure de Metaspace rapportée : 1 To ; au-delà la valeur n'est pas crédible. */
	static final long METASPACE_MO_MAX = 1024L * 1024L;

	private final DepotPlugins depot;

	private final EtatPluginServeurRepository etats;

	public DistributionController(DepotPlugins depot, EtatPluginServeurRepository etats) {
		this.depot = depot;
		this.etats = etats;
	}

	@GetMapping("/api/licence/plugins/catalogue")
	public ResponseEntity<List<EntreeCatalogue>> catalogue() {
		if (serveurAuthentifie() == null) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(depot.catalogue());
	}

	@GetMapping("/api/licence/plugins/{nom}/{version}/jar")
	public ResponseEntity<Resource> jar(@PathVariable String nom, @PathVariable String version) throws IOException {
		return fichier(nom, version, "jar");
	}

	@GetMapping("/api/licence/plugins/{nom}/{version}/sig")
	public ResponseEntity<Resource> sig(@PathVariable String nom, @PathVariable String version) throws IOException {
		return fichier(nom, version, "sig");
	}

	@PostMapping(path = "/api/licence/plugins/etat", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, String>> rapporter(HttpServletRequest requete) throws IOException {
		Long serveur = serveurAuthentifie();
		if (serveur == null) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		if (requete.getContentLengthLong() > TAILLE_MAX_RAPPORT) {
			return trop();
		}
		byte[] corps;
		try (InputStream in = requete.getInputStream()) {
			corps = in.readNBytes(TAILLE_MAX_RAPPORT + 1);
		}
		catch (IOException e) {
			// Client interrompu, délai de lecture... : la faute de l'appelant, pas du dépôt.
			log.warn("Plateforme : rapport du serveur {} illisible (corps de requête) : {}", serveur,
					e.getClass().getSimpleName());
			return erreur(HttpStatus.BAD_REQUEST, "corps de requête illisible");
		}
		if (corps.length > TAILLE_MAX_RAPPORT) {
			return trop();
		}
		JsonNode racine;
		try {
			racine = JSON.readTree(corps);
		}
		catch (IOException e) {
			throw new IllegalArgumentException("corps JSON invalide");
		}
		if (racine == null || !racine.isObject()) {
			throw new IllegalArgumentException("objet JSON attendu");
		}
		String nom = texte(racine, "nom");
		if (!ManifestePlugin.NOM_VALIDE.matcher(nom).matches()) {
			throw new IllegalArgumentException("nom de plugin invalide");
		}
		String version = texte(racine, "version");
		if (!DepotPlugins.versionValide(version)) {
			throw new IllegalArgumentException("version invalide (semver strict attendu)");
		}
		String statut = texte(racine, "statut");
		if (Arrays.stream(ResultatInstallation.Statut.values()).noneMatch(s -> s.name().equals(statut))) {
			throw new IllegalArgumentException("statut invalide (OK, REFUSE, ECHEC ou ANNULE)");
		}
		List<String> raisons = raisons(racine.get("raisons"));
		if (depot.catalogue().stream().noneMatch(e -> e.nom().equals(nom))) {
			return erreur(HttpStatus.NOT_FOUND, "plugin absent du dépôt");
		}

		MetaspaceServeur metaspace = metaspace(racine.get("metaspace"));

		int lignes = etats.enregistrer(serveur, nom, version, statut, String.join("\n", raisons), Instant.now(),
				metaspace == null ? null : metaspace.metaspaceMo(), metaspace == null ? null : metaspace.seuilMo(),
				metaspace == null ? null : metaspace.redemarrageRecommande(), etatsMaxParServeur);
		if (lignes == 0) {
			log.warn("Plateforme : rapport du serveur {} pour {} refusé : plafond de {} plugins suivis atteint", serveur,
					nom, etatsMaxParServeur);
			return erreur(HttpStatus.CONFLICT,
					"plafond de plugins suivis pour ce serveur atteint (" + etatsMaxParServeur + ")");
		}
		log.info("Plateforme : serveur {} rapporte {} v{} : {}", serveur, nom, version, statut);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/api/plateforme/serveurs-etat")
	@PreAuthorize("hasRole('ADMIN')")
	public List<EtatServeur> serveursEtat(@RequestParam(name = "limite", required = false) String limite) {
		int n = LISTE_DEFAUT;
		if (limite != null) {
			try {
				n = Integer.parseInt(limite.trim());
			}
			catch (NumberFormatException e) {
				n = limite.trim().matches("[0-9]{1,30}") ? LISTE_MAX : 0;
			}
			if (n < 1) {
				throw new IllegalArgumentException("limite invalide (entier positif attendu)");
			}
		}
		return etats.findAllByOrderByLicenceServeurIdAscNomAsc(Limit.of(Math.min(n, LISTE_MAX)))
			.stream()
			.map(e -> new EtatServeur(e.getLicenceServeurId(), e.getNom(), e.getVersion(), e.getStatut(),
					e.getRaisons() == null || e.getRaisons().isEmpty() ? List.of() : List.of(e.getRaisons().split("\n")),
					e.getRapporteLe(),
					e.getMetaspaceMo() == null || e.getSeuilMo() == null || e.getRedemarrageRecommande() == null ? null
							: new MetaspaceServeur(e.getMetaspaceMo(), e.getSeuilMo(), e.getRedemarrageRecommande())))
			.toList();
	}

	@ExceptionHandler(IllegalArgumentException.class)
	ResponseEntity<Map<String, String>> invalide(IllegalArgumentException e) {
		return erreur(HttpStatus.BAD_REQUEST, DepotPlugins.net(e.getMessage()));
	}

	@ExceptionHandler(NoSuchFileException.class)
	ResponseEntity<Map<String, String>> absente(NoSuchFileException e) {
		return erreur(HttpStatus.NOT_FOUND, "version absente du dépôt");
	}

	@ExceptionHandler(IOException.class)
	ResponseEntity<Map<String, String>> indisponible(IOException e) {
		log.error("Distribution : lecture du dépôt impossible : {}", DepotPlugins.net(e.getMessage()));
		return erreur(HttpStatus.INTERNAL_SERVER_ERROR, "dépôt indisponible (voir le journal du Maître)");
	}

	// ---- Utilitaires ----

	/**
	 * Fichier d'une paire complète et vérifiée, ouvert SANS suivre de lien ; {@code Content-Length} = taille du
	 * fichier effectivement ouvert. Le flux est fermé par le convertisseur après l'écriture de la réponse.
	 */
	private ResponseEntity<Resource> fichier(String nom, String version, String extension) throws IOException {
		if (serveurAuthentifie() == null) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		Path chemin = depot.ouvrir(nom, version, extension);
		SeekableByteChannel canal = Files.newByteChannel(chemin, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
		try {
			long taille = canal.size();
			String nomFichier = nom + "-" + version + (extension.equals("jar") ? ".jar" : ".jar.sig");
			return ResponseEntity.ok()
				.contentType(extension.equals("jar") ? JAR : MediaType.APPLICATION_JSON)
				.contentLength(taille)
				.cacheControl(CacheControl.noStore())
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.attachment().filename(nomFichier).build().toString())
				.body(new InputStreamResource(Channels.newInputStream(canal)));
		}
		catch (RuntimeException e) {
			canal.close();
			throw e;
		}
	}

	/** Id du {@code LicenceServeur} authentifié par sa clé API, ou {@code null} (garde défensive : 403). */
	private static Long serveurAuthentifie() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth != null && auth.getPrincipal() instanceof Long id) {
			return id;
		}
		log.warn("Distribution : appel refusé, principal inattendu");
		return null;
	}

	private static String texte(JsonNode racine, String champ) {
		JsonNode n = racine.get(champ);
		if (n == null || !n.isTextual()) {
			throw new IllegalArgumentException("champ texte attendu : " + champ);
		}
		return n.asText();
	}

	/**
	 * Raisons nettoyées (sans saut de ligne : séparateur de stockage), les vides et blanches écartées (le format
	 * stocké est ainsi relu à l'identique), au plus {@value #RAISONS_MAX}.
	 */
	private static List<String> raisons(JsonNode n) {
		if (n == null || n.isNull()) {
			return List.of();
		}
		if (!n.isArray()) {
			throw new IllegalArgumentException("raisons : tableau de textes attendu");
		}
		List<String> nettes = new ArrayList<>();
		for (JsonNode r : n) {
			if (!r.isTextual()) {
				throw new IllegalArgumentException("raisons : tableau de textes attendu");
			}
			String nette = DepotPlugins.net(r.asText());
			if (!nette.isBlank()) {
				nettes.add(nette);
			}
		}
		if (nettes.size() > RAISONS_MAX) {
			int ecartees = nettes.size() - (RAISONS_MAX - 1);
			nettes = new ArrayList<>(nettes.subList(0, RAISONS_MAX - 1));
			nettes.add("(" + ecartees + " raisons non conservées)");
		}
		return nettes;
	}

	/**
	 * Mesure du Metaspace rapportée, NON FIABLE : exactement un objet {@code {metaspaceMo, seuilMo,
	 * redemarrageRecommande}} d'entiers dans {@code [0, METASPACE_MO_MAX]} et d'un booléen JSON ; sinon {@code null}
	 * (mesure ignorée, le rapport lui-même reste accepté : c'est un accessoire, un 400 ferait réessayer le serveur
	 * sans fin). Rien de la valeur reçue n'est journalisé ni stocké en texte.
	 */
	private static MetaspaceServeur metaspace(JsonNode n) {
		if (n == null || !n.isObject()) {
			return null;
		}
		JsonNode mo = n.get("metaspaceMo");
		JsonNode seuil = n.get("seuilMo");
		JsonNode recommande = n.get("redemarrageRecommande");
		if (!mesureValide(mo) || !mesureValide(seuil) || recommande == null || !recommande.isBoolean()) {
			return null;
		}
		return new MetaspaceServeur(mo.longValue(), seuil.longValue(), recommande.booleanValue());
	}

	private static boolean mesureValide(JsonNode n) {
		return n != null && n.isIntegralNumber() && n.canConvertToLong() && n.longValue() >= 0
				&& n.longValue() <= METASPACE_MO_MAX;
	}

	private static ResponseEntity<Map<String, String>> trop() {
		return erreur(HttpStatus.CONTENT_TOO_LARGE, "rapport trop volumineux (" + TAILLE_MAX_RAPPORT + " octets au plus)");
	}

	/** Réponse d'erreur au Content-Type JSON fixé (pas de négociation avec {@code Accept}). */
	private static ResponseEntity<Map<String, String>> erreur(HttpStatus statut, String message) {
		return ResponseEntity.status(statut).contentType(MediaType.APPLICATION_JSON).body(Map.of("erreur", message));
	}

}
