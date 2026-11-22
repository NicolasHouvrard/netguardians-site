package com.logicielpourtous.site.features.ui;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import com.logicielpourtous.site.features.plateforme.NavigationService.Tuile;

/**
 * Page de demonstration des gabarits du socle (reservee a l'admin par la chaine de securite web :
 * /admin/** est limite a ROLE_ADMIN). Donnees factices en dur, aucune base.
 */
@Controller
public class SocleStyleguideController {

	private static final String FRAGMENT_LISTE = "socle/fragments/list-view :: list-view(colonnes=${colonnes},lignes=${lignes},urlLigne=${urlLigne})";

	private static final List<Map<String, Object>> COLONNES = List.of(
			Map.of("cle", "id", "libelle", "N°", "tri", true), Map.of("cle", "nom", "libelle", "Nom", "tri", true),
			Map.of("cle", "ville", "libelle", "Ville", "tri", false),
			Map.of("cle", "etat", "libelle", "État", "tri", false));

	private static final List<Map<String, Object>> DONNEES = List.of(
			Map.of("id", 1, "nom", "Alpha Informatique", "ville", "Lyon", "etat", "Actif"),
			Map.of("id", 2, "nom", "Bravo Services", "ville", "Nantes", "etat", "Actif"),
			Map.of("id", 3, "nom", "Charlie Conseil", "ville", "Lille", "etat", "Brouillon"),
			Map.of("id", 4, "nom", "Delta Réseaux", "ville", "Toulouse", "etat", "Suspendu"),
			Map.of("id", 5, "nom", "Echo Logiciels", "ville", "Rennes", "etat", "Actif"));

	private static final List<Map<String, Object>> ETATS = List.of(Map.of("code", "brouillon", "libelle", "Brouillon"),
			Map.of("code", "actif", "libelle", "Actif"), Map.of("code", "suspendu", "libelle", "Suspendu"));

	@GetMapping("/admin/socle")
	public String page(Model model) {
		return pageComplete(model, "");
	}

	private String pageComplete(Model model, String q) {
		remplirListe(model, q);
		model.addAttribute("titre", "Socle : gabarits");
		model.addAttribute("moduleActif", "Socle");
		model.addAttribute("fil", List.of(Map.of("libelle", "Administration", "url", "/admin"),
				Map.of("libelle", "Gabarits du socle")));
		model.addAttribute("actionsPanneau", List.of(Map.of("libelle", "Nouveau", "url", "/admin/socle")));
		model.addAttribute("recherche", Map.of("url", "/admin/socle/liste", "cible", "#liste"));
		model.addAttribute("etats", ETATS);
		model.addAttribute("courant", "actif");
		model.addAttribute("statutFiche", Map.of("etats", ETATS, "courant", "actif"));
		model.addAttribute("actionsFiche", List.of(Map.of("libelle", "Modifier", "url", "/admin/socle")));
		model.addAttribute("tuilesDemo", List.of(new Tuile("demo", "Clients", "bi-people", "/admin/socle", null, "1"),
				new Tuile("demo", "Factures", "bi-receipt", "/admin/socle", null, "1"),
				new Tuile("demo", "Réglages", "bi-gear", "/admin/socle", null, "1")));
		return "socle/styleguide";
	}

	@GetMapping("/admin/socle/liste")
	public String liste(@RequestParam(name = "q", defaultValue = "") String q,
			@RequestHeader(name = "HX-Request", required = false) String hxRequest, Model model) {
		remplirListe(model, q);
		if ("true".equalsIgnoreCase(hxRequest)) {
			return FRAGMENT_LISTE;
		}
		return pageComplete(model, q);
	}

	private void remplirListe(Model model, String q) {
		String motif = q.trim().toLowerCase(Locale.ROOT);
		List<Map<String, Object>> lignes = DONNEES.stream()
				.filter(l -> motif.isEmpty() || ((String) l.get("nom")).toLowerCase(Locale.ROOT).contains(motif)
						|| ((String) l.get("ville")).toLowerCase(Locale.ROOT).contains(motif))
				.toList();
		model.addAttribute("colonnes", COLONNES);
		model.addAttribute("lignes", lignes);
		model.addAttribute("urlLigne", null);
	}
}
