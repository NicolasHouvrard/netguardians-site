package com.logicielpourtous.site.features.plateforme;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import com.logicielpourtous.site.features.plateforme.AppsAdvice.Apps;
import com.logicielpourtous.site.features.plateforme.AppsAdvice.TuileApp;

/**
 * Accueil à tuiles ({@code /accueil}) : les applications que l'utilisateur courant peut ouvrir. Réservé aux utilisateurs
 * authentifiés par la chaîne de sécurité (règle explicite). Les tuiles viennent de {@link NavigationService}, qui
 * applique les droits ; une erreur de lecture donne l'écran sans application, jamais une erreur serveur.
 */
@Controller
@ConditionalOnProperty(name = "app.plateforme.actif", havingValue = "true")
public class AccueilController {

	@GetMapping("/accueil")
	public String accueil(@RequestParam(name = "q", defaultValue = "") String q,
			@RequestHeader(name = "HX-Request", required = false) String hxRequest,
			@ModelAttribute("apps") Apps apps, Model model) {
		String motif = normaliser(q);
		List<TuileApp> grille = apps.tuiles().stream()
				.filter(t -> motif.isEmpty() || normaliser(t.libelle()).contains(motif)).toList();
		model.addAttribute("tuilesGrille", grille);
		model.addAttribute("q", q);
		if ("true".equalsIgnoreCase(hxRequest)) {
			return "socle/accueil :: grille";
		}
		model.addAttribute("titre", "Accueil");
		model.addAttribute("moduleActif", "Accueil");
		return "socle/accueil";
	}

	/** Minuscules sans accents ni espaces de bord, pour comparer sans tenir compte de la casse ni des accents. */
	static String normaliser(String texte) {
		if (texte == null) {
			return "";
		}
		return Normalizer.normalize(texte.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
				.toLowerCase(Locale.ROOT);
	}

}
