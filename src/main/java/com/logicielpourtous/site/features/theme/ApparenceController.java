package com.logicielpourtous.site.features.theme;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.plateforme.NavigationService.Tuile;
import com.logicielpourtous.site.features.theme.ThemeService.Etat;
import com.logicielpourtous.site.features.theme.ThemeService.Origine;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Ecran d'administration « Apparence » (reserve a ROLE_ADMIN par la chaine de securite web, CSRF exige).
 * <ul>
 * <li>L'identifiant d'administrateur journalise vient UNIQUEMENT de la session authentifiee.</li>
 * <li>Aucune valeur saisie n'est jamais recopiee : l'apercu et les ecrans sont generes a partir de valeurs validees
 * par {@link ThemeValeurs} (normalisees) ; les erreurs sont des textes fixes, sans echo.</li>
 * </ul>
 */
@Controller
@RequestMapping("/admin/apparence")
public class ApparenceController {

	private static final String VUE_PAGE = "admin/apparence";
	private static final String VUE_GROUPE = "admin/apparence-groupe :: groupe(groupe=${groupe})";
	private static final String VUE_APERCU = "admin/apparence-apercu :: apercu(styleApercu=${styleApercu})";
	private static final String VUE_APERCU_ERREUR = "admin/apparence-apercu :: erreur";
	private static final String VUE_VIDE = "admin/apparence-apercu :: vide";
	private static final String VUE_REFUS = "admin/apparence-apercu :: refus";

	/** Libelles (texte fixe) des reglages. */
	private static final Map<String, String> LIBELLES = libelles();

	/** Donnees factices de l'apercu (textes fixes). */
	private static final List<Map<String, Object>> COLONNES = List.of(Map.of("cle", "nom", "libelle", "Nom", "tri", true),
			Map.of("cle", "ville", "libelle", "Ville", "tri", false), Map.of("cle", "etat", "libelle", "État", "tri", false));
	private static final List<Map<String, Object>> LIGNES = List.of(
			Map.of("id", 1, "nom", "Alpha Informatique", "ville", "Lyon", "etat", "Actif"),
			Map.of("id", 2, "nom", "Bravo Services", "ville", "Nantes", "etat", "Brouillon"),
			Map.of("id", 3, "nom", "Charlie Conseil", "ville", "Lille", "etat", "Actif"));
	private static final List<Map<String, Object>> ETATS = List.of(Map.of("code", "brouillon", "libelle", "Brouillon"),
			Map.of("code", "actif", "libelle", "Actif"), Map.of("code", "suspendu", "libelle", "Suspendu"));
	private static final List<Tuile> TUILES = List.of(new Tuile("apercu", "Clients", "bi-people", "/admin/apparence", null, "1"),
			new Tuile("apercu", "Factures", "bi-receipt", "/admin/apparence", null, "1"));

	/** Reglage affiche : tout est issu de {@link ThemeValeurs} / {@link ThemeService}, jamais de la requete. */
	public record Ligne(String cle, String libelle, String genre, int min, int max, List<String> choix, String valeur,
			boolean modifie, String reference) {
	}

	/** Onglet : un groupe de reglages. */
	public record Groupe(String id, String libelle, List<Ligne> lignes) {
	}

	private final ThemeService service;

	public ApparenceController(ThemeService service) {
		this.service = service;
	}

	@GetMapping
	public String page(Model model) {
		model.addAttribute("groupes", groupes());
		remplirApercu(model, styleApercu(effectives(), Map.of()));
		model.addAttribute("titre", "Apparence");
		model.addAttribute("moduleActif", "Apparence");
		model.addAttribute("fil", List.of(Map.of("libelle", "Administration", "url", "/admin"), Map.of("libelle", "Apparence")));
		return VUE_PAGE;
	}

	/** Apercu : valeurs en cours de saisie (paires cle/valeur), jamais persistees. */
	@PostMapping("/apercu")
	public String apercu(@RequestParam(name = "cle", required = false) List<String> cles,
			@RequestParam(name = "valeur", required = false) List<String> valeurs, Model model) {
		try {
			if (cles == null || valeurs == null || cles.size() != valeurs.size() || cles.size() > ThemeValeurs.cles().size()) {
				return VUE_APERCU_ERREUR;
			}
			Map<String, String> saisies = new LinkedHashMap<>();
			for (int i = 0; i < cles.size(); i++) {
				saisies.put(cles.get(i), ThemeValeurs.valider(cles.get(i), valeurs.get(i)));
			}
			String style = styleApercu(effectives(), saisies);
			remplirApercu(model, style);
			return VUE_APERCU;
		} catch (RuntimeException e) {
			return VUE_APERCU_ERREUR;
		}
	}

	@PostMapping("/reinitialiser")
	public String toutReinitialiser(@AuthenticationPrincipal CustomUserDetails admin,
			@RequestHeader(name = "HX-Request", required = false) String hx, HttpServletResponse reponse) {
		Long id = identifiant(admin);
		if (id == null) {
			return refuser(reponse, HttpServletResponse.SC_FORBIDDEN);
		}
		service.toutReinitialiser(id);
		if (estHtmx(hx)) {
			reponse.setHeader("HX-Redirect", "/admin/apparence");
			return VUE_VIDE;
		}
		return "redirect:/admin/apparence";
	}

	@PostMapping("/{cle}")
	public String enregistrer(@PathVariable("cle") String cle,
			@RequestParam(name = "valeur", required = false) String valeur,
			@AuthenticationPrincipal CustomUserDetails admin,
			@RequestHeader(name = "HX-Request", required = false) String hx, Model model, HttpServletResponse reponse) {
		Long id = identifiant(admin);
		if (id == null) {
			return refuser(reponse, HttpServletResponse.SC_FORBIDDEN);
		}
		if (!ThemeValeurs.cles().contains(cle)) {
			return refuser(reponse, HttpServletResponse.SC_NOT_FOUND);
		}
		try {
			ThemeValeurs.valider(cle, valeur);
		} catch (IllegalArgumentException e) {
			return refuser(reponse, HttpServletResponse.SC_BAD_REQUEST);
		}
		service.definir(id, cle, valeur);
		return suite(cle, hx, model);
	}

	@PostMapping("/{cle}/reinitialiser")
	public String reinitialiser(@PathVariable("cle") String cle, @AuthenticationPrincipal CustomUserDetails admin,
			@RequestHeader(name = "HX-Request", required = false) String hx, Model model, HttpServletResponse reponse) {
		Long id = identifiant(admin);
		if (id == null) {
			return refuser(reponse, HttpServletResponse.SC_FORBIDDEN);
		}
		if (!ThemeValeurs.cles().contains(cle)) {
			return refuser(reponse, HttpServletResponse.SC_NOT_FOUND);
		}
		service.reinitialiser(id, cle);
		return suite(cle, hx, model);
	}

	// ---------- utilitaires ----------

	/** Identifiant de l'administrateur connecte ; {@code null} s'il est indeterminable (rien n'est alors ecrit). */
	private static Long identifiant(CustomUserDetails admin) {
		return admin == null ? null : admin.getUserId();
	}

	private static boolean estHtmx(String hx) {
		return "true".equalsIgnoreCase(hx);
	}

	/** Reponse de refus : statut seul et texte fixe (jamais d'echo de la requete). */
	private static String refuser(HttpServletResponse reponse, int statut) {
		reponse.setStatus(statut);
		return VUE_REFUS;
	}

	private String suite(String cle, String hx, Model model) {
		if (!estHtmx(hx)) {
			return "redirect:/admin/apparence";
		}
		String groupe = ThemeValeurs.groupe(cle);
		model.addAttribute("groupe", groupes().stream().filter(g -> g.id().equals(groupe)).findFirst().orElseThrow());
		remplirApercu(model, styleApercu(effectives(), Map.of()));
		return VUE_GROUPE;
	}

	private Map<String, String> effectives() {
		Map<String, String> m = new LinkedHashMap<>();
		service.reglages().forEach((c, e) -> m.put(c, e.valeur()));
		return m;
	}

	private List<Groupe> groupes() {
		Map<String, Etat> etat = service.reglages();
		List<Groupe> resultat = new ArrayList<>();
		for (Map.Entry<String, String> g : ThemeValeurs.GROUPES.entrySet()) {
			List<Ligne> lignes = new ArrayList<>();
			for (String cle : ThemeValeurs.cles()) {
				if (!g.getKey().equals(ThemeValeurs.groupe(cle))) {
					continue;
				}
				Etat e = etat.get(cle);
				ThemeValeurs.Controle c = ThemeValeurs.controle(cle);
				String ref = e.reference() == null ? null : LIBELLES.getOrDefault(e.reference(), e.reference());
				lignes.add(new Ligne(cle, LIBELLES.getOrDefault(cle, cle), c.genre(), c.min(), c.max(), c.choix(), e.valeur(),
						e.origine() == Origine.MODIFIE, ref));
			}
			resultat.add(new Groupe(g.getKey(), g.getValue(), lignes));
		}
		return resultat;
	}

	/**
	 * Variables {@code --socle-*} de l'apercu : valeurs effectives (deja validees) recouvertes par les saisies
	 * VALIDEES, chacune re-generee par {@link ThemeValeurs#rendreCss}. Aucun texte de la requete n'y passe.
	 */
	private static String styleApercu(Map<String, String> effectives, Map<String, String> saisiesValidees) {
		Map<String, String> v = new LinkedHashMap<>(effectives);
		v.putAll(saisiesValidees);
		StringBuilder sb = new StringBuilder();
		for (String cle : ThemeValeurs.cles()) {
			sb.append("--socle-").append(cle).append(':').append(ThemeValeurs.rendreCss(cle, v.get(cle))).append(';');
		}
		ThemeValeurs.variablesDerivees(v.get("liste-densite"), v.get("liste-lignes-alternees"), v.get("fiche-statut-style"))
				.forEach((nom, valeur) -> sb.append("--socle-").append(nom).append(':').append(valeur).append(';'));
		return sb.toString();
	}

	private static void remplirApercu(Model model, String style) {
		model.addAttribute("styleApercu", style);
		model.addAttribute("apercuColonnes", COLONNES);
		model.addAttribute("apercuLignes", LIGNES);
		model.addAttribute("apercuEtats", ETATS);
		model.addAttribute("apercuTuiles", TUILES);
	}

	private static Map<String, String> libelles() {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("couleur-primaire", "Couleur primaire");
		m.put("couleur-secondaire", "Couleur secondaire");
		m.put("couleur-fond", "Couleur de fond");
		m.put("couleur-texte", "Couleur du texte");
		m.put("couleur-bordure", "Couleur des bordures");
		m.put("police", "Police");
		m.put("taille-texte", "Taille du texte (px)");
		m.put("epaisseur-trait", "Épaisseur des traits (px)");
		m.put("arrondi", "Arrondi général (px)");
		m.put("topbar-fond", "Fond de la barre supérieure");
		m.put("topbar-texte", "Texte de la barre supérieure");
		m.put("topbar-hauteur", "Hauteur de la barre supérieure (px)");
		m.put("tuile-fond", "Fond des tuiles");
		m.put("tuile-arrondi", "Arrondi des tuiles (px)");
		m.put("tuile-taille-icone", "Taille des icônes de tuile (px)");
		m.put("app-primaire", "Application : primaire");
		m.put("app-violet", "Application : violet");
		m.put("app-sarcelle", "Application : sarcelle");
		m.put("app-orange", "Application : orange");
		m.put("app-ambre", "Application : ambre");
		m.put("app-rose", "Application : rose");
		m.put("app-ardoise", "Application : ardoise");
		m.put("app-vert", "Application : vert");
		m.put("liste-densite", "Densité des listes");
		m.put("liste-lignes-alternees", "Lignes alternées");
		m.put("liste-entete-fond", "Fond de l'en-tête de liste");
		m.put("fiche-statut-style", "Style de la barre de statut");
		m.put("fiche-statut-actif-fond", "Fond du statut actif");
		m.put("bouton-arrondi", "Arrondi des boutons (px)");
		m.put("bouton-primaire-fond", "Fond du bouton principal");
		return java.util.Collections.unmodifiableMap(m);
	}
}
