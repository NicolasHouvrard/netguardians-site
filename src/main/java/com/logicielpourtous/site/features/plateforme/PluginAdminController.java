package com.logicielpourtous.site.features.plateforme;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Administration de la plateforme de plugins ({@code /api/plateforme/**}, réservé à {@code ROLE_ADMIN} par la chaîne
 * de sécurité ; la garde de méthode est une seconde barrière).
 * <p>
 * L'installation ne prend qu'un NOM de fichier, résolu dans le répertoire des plugins configuré : un nom qui contient
 * {@code /}, {@code \} ou {@code ..} est refusé (400), et le chemin résolu doit rester dans ce répertoire et désigner un
 * fichier ordinaire (sinon 404). Le résultat d'installation est toujours renvoyé en 200 : son {@code statut} dit si la
 * version est active ({@code OK}), refusée, en échec ou annulée.
 */
@RestController
@RequestMapping("/api/plateforme")
@PreAuthorize("hasRole('ADMIN')")
@ConditionalOnProperty(name = "app.plateforme.actif", havingValue = "true")
public class PluginAdminController {

	/** Corps de {@code POST /plugins/installer}. */
	public record DemandeInstallation(String fichier) {
	}

	private final PluginManager manager;

	private final PlateformeProperties proprietes;

	public PluginAdminController(PluginManager manager, PlateformeProperties proprietes) {
		this.manager = manager;
		this.proprietes = proprietes;
	}

	@GetMapping("/plugins")
	public List<EtatPlugin> lister() {
		return manager.etats();
	}

	@PostMapping("/plugins/installer")
	public ResultatInstallation installer(@RequestBody DemandeInstallation demande) {
		return manager.installer(resoudre(demande == null ? null : demande.fichier()));
	}

	@DeleteMapping("/plugins/{nom}")
	public ResponseEntity<Void> desinstaller(@PathVariable String nom) {
		if (!manager.desinstaller(nom)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Plugin inconnu : " + nom);
		}
		return ResponseEntity.noContent().build();
	}

	/**
	 * Rapport de fuites. {@code pression=true} n'est honoré que si {@code app.plateforme.diagnostic-fuites=true}
	 * (sinon ignoré par le gestionnaire).
	 */
	@GetMapping("/fuites")
	public RapportFuites fuites(@RequestParam(name = "pression", defaultValue = "false") boolean pression) {
		return manager.rapportFuites(pression);
	}

	/** Fichier {@code nom} du répertoire des plugins, ou 400 (nom qui n'est pas un simple nom) / 404 (absent). */
	private Path resoudre(String nom) {
		if (nom == null || nom.isBlank() || nom.contains("/") || nom.contains("\\") || nom.contains("..")
				|| nom.indexOf(':') >= 0 || nom.indexOf('\0') >= 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Nom de fichier attendu, sans chemin (ni '/', ni '\\', ni '..')");
		}
		Path repertoire = Path.of(proprietes.getRepertoirePlugins()).toAbsolutePath().normalize();
		Path fichier = repertoire.resolve(nom).normalize();
		if (!repertoire.equals(fichier.getParent())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fichier hors du répertoire des plugins");
		}
		if (!Files.isRegularFile(fichier, LinkOption.NOFOLLOW_LINKS)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Fichier absent du répertoire des plugins : " + nom);
		}
		return fichier;
	}

}
