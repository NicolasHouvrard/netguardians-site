package com.logicielpourtous.site.features.plateforme;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.logicielpourtous.site.features.plateforme.NavigationService.BlocAutorise;
import com.logicielpourtous.site.features.plateforme.NavigationService.Tuile;
import com.logicielpourtous.site.sdk.ZoneNavigation;

/**
 * Navigation des plugins ({@code /api/navigation/**}) : accessible à tout compte authentifié (la chaîne de sécurité
 * refuse l'anonyme en 401 et le jeton « choix de société » en 403). Ce que l'utilisateur voit est décidé par
 * {@link NavigationService} d'après SES droits ; aucun identifiant d'utilisateur n'est lu dans la requête.
 */
@RestController
@RequestMapping("/api/navigation")
@ConditionalOnProperty(name = "app.plateforme.actif", havingValue = "true")
public class NavigationController {

	private final NavigationService service;

	public NavigationController(NavigationService service) {
		this.service = service;
	}

	@GetMapping
	public List<Tuile> tuiles(@RequestParam(required = false) String zone,
			@RequestParam(required = false) Long societeId) {
		if (zone == null || zone.isBlank()) {
			// Zone décidée par le serveur d'après les capacités de la société (chaque tuile porte sa zone).
			return service.tuilesAutorisees(societeId);
		}
		return service.tuiles(zone(zone), societeId);
	}

	@GetMapping("/blocs")
	public List<BlocAutorise> blocs(@RequestParam(required = false) Long societeId) {
		return service.blocs(societeId);
	}

	/** Validation de la zone avant tout travail. */
	private static ZoneNavigation zone(String valeur) {
		String attendu = Arrays.toString(ZoneNavigation.values());
		if (valeur == null || valeur.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Zone inconnue.");
		}
		try {
			return ZoneNavigation.valueOf(valeur.trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Zone inconnue : '" + valeur + "' (valeurs : " + attendu + ").");
		}
	}

}
