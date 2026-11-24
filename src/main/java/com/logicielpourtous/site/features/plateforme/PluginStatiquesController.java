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
 * Sert les fichiers statiques d'un plugin : {@code GET /p/<nom>/static/<fichier>}, depuis {@code static/<fichier>}
 * dans le jar de la génération ACTIVE. Session requise (règle {@code /p/**} de la sécurité).
 * <p>
 * Un seul segment de nom simple ({@code [A-Za-z0-9._-]}, ni {@code ..}), extensions {@code css}, {@code js},
 * {@code svg}, {@code png}, {@code jpg}, {@code woff2} seulement, avec le type MIME correspondant et
 * {@code X-Content-Type-Options: nosniff}. Un SVG porte en plus une politique de sécurité qui neutralise tout script.
 * Plugin inconnu, génération non annoncée, fichier absent ou refusé : le MÊME 404 sans corps.
 * <p>
 * La génération est celle annoncée (et comptée en vol) par {@link FiltreEnVol} : la lecture du jar ne peut pas se faire
 * sur un chargeur fermé par une bascule. L'espace {@code /p/<nom>/static/**} est RÉSERVÉ au noyau : {@link RoutagePlugins}
 * n'y route jamais un mapping du plugin.
 */
@RestController
@ConditionalOnProperty(name = "app.plateforme.actif", havingValue = "true")
public class PluginStatiquesController {

	private static final Pattern NOM_DE_FICHIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

	private static final Map<String, String> TYPES = Map.of(
			"css", "text/css",
			"js", "text/javascript",
			"svg", "image/svg+xml",
			"png", "image/png",
			"jpg", "image/jpeg",
			"woff2", "font/woff2");

	@GetMapping("/p/{nom}/static/{fichier}")
	public ResponseEntity<byte[]> fichier(@PathVariable String nom, @PathVariable String fichier,
			HttpServletRequest requete) throws IOException {
		Object generation = requete.getAttribute(RoutagePlugins.ATTRIBUT_GENERATION);
		PluginClassLoader chargeur = generation instanceof PluginGeneration gen && gen.nom.equals(nom) ? gen.loader
				: null;
		if (chargeur == null || !nomValide(fichier)) {
			return introuvable();
		}
		String type = TYPES.get(fichier.substring(fichier.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT));
		if (type == null) {
			return introuvable();
		}
		// findResource : uniquement le jar du plugin (getResource retomberait sur le chargeur du cœur).
		URL url = chargeur.findResource("static/" + fichier);
		if (url == null) {
			return introuvable();
		}
		byte[] contenu;
		try (InputStream in = url.openStream()) {
			contenu = in.readAllBytes();
		}
		ResponseEntity.BodyBuilder reponse = ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_TYPE, type)
				.header("X-Content-Type-Options", "nosniff")
				// L'URL ne porte pas la version du plugin : toujours revalider après une mise à jour.
				.header(HttpHeaders.CACHE_CONTROL, "private, no-cache");
		if ("image/svg+xml".equals(type)) {
			// Aucun script, aucune ressource externe, même si le SVG est ouvert directement.
			reponse.header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox");
		}
		return reponse.body(contenu);
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
