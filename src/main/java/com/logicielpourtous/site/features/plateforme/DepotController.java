package com.logicielpourtous.site.features.plateforme;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Dépôt du Maître ({@code /api/plateforme/depot}, réservé à {@code ROLE_ADMIN} par la chaîne de sécurité de
 * {@code /api/plateforme/**} ; la garde de méthode est une seconde barrière). N'existe que si
 * {@code app.plateforme.actif=true} et {@code app.role=MASTER}.
 * <p>
 * {@code POST} (multipart {@code jar} et {@code sig}) publie une version ; le nom de fichier annoncé par le client est
 * ignoré (nom et version viennent du manifeste vérifié). Réponses : 200 + entrée publiée (aussi pour une republication
 * identique), 400 signature / manifeste / version invalides ou partie manquante, 409 même version au contenu
 * différent, 413 au-delà de {@code app.plateforme.taille-max-jar-mo}, 500 dépôt inutilisable. Corps d'erreur
 * {@code {"erreur": "..."}} au texte nettoyé. {@code GET} renvoie le catalogue.
 * <p>
 * <b>Limite effective de publication : 10 Mo par jar.</b> Le multipart Spring
 * ({@code spring.servlet.multipart.max-file-size=10MB}, réglage global de l'application) rejette une requête plus
 * grosse AVANT ce contrôleur, sans corps {@code {"erreur": ...}} (le client peut même voir la connexion coupée). La
 * borne {@code app.plateforme.taille-max-jar-mo} (défaut 100) n'agit donc ici que si elle est inférieure à 10 ; elle
 * reste la borne des jars installés par le cœur. Choix assumé : les jars de plugin sont minces (bibliothèques
 * partagées dans le cœur) ; publier plus gros demanderait un autre contrat (corps brut en flux) plutôt que de relever
 * la limite globale.
 */
@RestController
@RequestMapping("/api/plateforme/depot")
@PreAuthorize("hasRole('ADMIN')")
@ConditionalOnProperty(name = "app.plateforme.actif", havingValue = "true")
@Conditional(SurServeurMaitre.class)
public class DepotController {

	private static final Logger log = LoggerFactory.getLogger(DepotController.class);

	private final DepotPlugins depot;

	public DepotController(DepotPlugins depot) {
		this.depot = depot;
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public EntreeCatalogue publier(@RequestPart("jar") MultipartFile jar, @RequestPart("sig") MultipartFile sig)
			throws IOException {
		try (InputStream j = jar.getInputStream(); InputStream s = sig.getInputStream()) {
			return depot.publier(j, s);
		}
	}

	@GetMapping
	public List<EntreeCatalogue> catalogue() {
		return depot.catalogue();
	}

	@ExceptionHandler(DepotPlugins.DepotRefuse.class)
	ResponseEntity<Map<String, String>> refus(DepotPlugins.DepotRefuse e) {
		HttpStatus statut = switch (e.motif()) {
			case INVALIDE -> HttpStatus.BAD_REQUEST;
			case TROP_VOLUMINEUX -> HttpStatus.PAYLOAD_TOO_LARGE;
			case CONFLIT -> HttpStatus.CONFLICT;
		};
		return ResponseEntity.status(statut).body(Map.of("erreur", e.getMessage()));
	}

	@ExceptionHandler(IOException.class)
	ResponseEntity<Map<String, String>> indisponible(IOException e) {
		log.error("Dépôt : publication impossible : {}", e.getMessage());
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
			.body(Map.of("erreur", "dépôt indisponible (voir le journal du Maître)"));
	}

}
