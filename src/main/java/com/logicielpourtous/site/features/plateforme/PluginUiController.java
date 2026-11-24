package com.logicielpourtous.site.features.plateforme;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sert les fichiers du front d'un plugin : {@code GET /api/plugins/<nom>/ui/<version>/<fichier>}.
 * <p>
 * Un {@code <script type="module">} ne peut pas porter d'en-tête {@code Authorization} : cette route est la SEULE de
 * {@code /api/plugins/**} ouverte sans jeton (voir {@code SecurityConfig}). Elle ne sert donc que du code statique du
 * dossier {@code ui/} du jar du plugin ACTIF, jamais de donnée : un seul segment de nom simple, extensions
 * {@code js}, {@code css}, {@code json} seulement, et uniquement si {@code <version>} est celle du plugin actif (sinon
 * 404, comme pour un plugin ou un fichier inconnu : aucune information sur ce qui existe). L'URL portant la version,
 * la réponse est immuable.
 * <p>
 * Contrat pour les plugins : les fichiers du front sont dans {@code ui/<fichier>} À LA RACINE du jar (la version
 * n'apparaît que dans l'URL, jamais dans le jar) ; l'espace {@code /api/plugins/<nom>/ui/**} est RÉSERVÉ au noyau :
 * {@link RoutagePlugins} n'y route jamais un mapping du plugin (un contrôleur de plugin ne peut pas y répondre).
 */
@RestController
@ConditionalOnProperty(name = "app.plateforme.actif", havingValue = "true")
public class PluginUiController {

	private static final Pattern NOM_DE_FICHIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

	private static final Map<String, String> TYPES = Map.of(
			"js", "text/javascript",
			"css", "text/css",
			"json", "application/json");

	/**
	 * La génération est celle annoncée (et comptée en vol) par {@link FiltreEnVol} : la lecture du jar ne peut donc pas
	 * se faire sur un chargeur fermé par une bascule. Attribut absent ou {@code AUCUNE_GENERATION} : 404.
	 */
	@GetMapping("/api/plugins/{nom}/ui/{version}/{fichier}")
	public ResponseEntity<byte[]> fichier(@PathVariable String nom, @PathVariable String version,
			@PathVariable String fichier,
			HttpServletRequest requete) throws IOException {
		Object generation = requete.getAttribute(RoutagePlugins.ATTRIBUT_GENERATION);
		if (!(generation instanceof PluginGeneration gen) || !gen.nom.equals(nom) || !gen.version.equals(version)
				|| gen.loader == null || !nomValide(fichier)) {
			return introuvable();
		}
		String type = TYPES.get(fichier.substring(fichier.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT));
		if (type == null) {
			return introuvable();
		}
		// findResource : uniquement le jar du plugin (getResource retomberait sur le chargeur parent).
		URL url = gen.loader.findResource("ui/" + fichier);
		if (url == null) {
			return introuvable();
		}
		byte[] contenu;
		try (InputStream in = url.openStream()) {
			contenu = in.readAllBytes();
		}
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_TYPE, type)
				.header("X-Content-Type-Options", "nosniff")
				.header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
				.body(contenu);
	}

	/** Nom simple : lettres, chiffres, point, tiret, souligné ; ni séparateur, ni {@code ..}, ni octet nul, ni espace. */
	private static boolean nomValide(String fichier) {
		return fichier != null && NOM_DE_FICHIER.matcher(fichier).matches() && !fichier.contains("..")
				&& fichier.indexOf('.') > 0 && !fichier.endsWith(".");
	}

	private static ResponseEntity<byte[]> introuvable() {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
	}

}
