package com.logicielpourtous.site.plugins.partenaires;

import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.CreationClient;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.ResultatCreation;
import com.logicielpourtous.site.sdk.GestionPartenairesContrat;
import com.logicielpourtous.site.sdk.GestionPartenairesContrat.CreationPartenaire;
import com.logicielpourtous.site.sdk.GestionPartenairesContrat.ResultatPartenaire;
import com.logicielpourtous.site.sdk.ManifestePlugin;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

/**
 * Pages de gestion des partenaires, servies sous {@code /p/partenaires} par le noyau (session et CSRF cote noyau). Le
 * plugin ne verifie aucun role et ne lit aucune identite : le contrat {@link GestionPartenairesContrat} refuse tout
 * appel qui ne vient pas de l'administrateur du serveur maitre ({@link SecurityException}), traduit ici en 403 avec une
 * page fixe. Toute autre erreur inattendue donne un 500 a page fixe (echec ferme) ; une demande invalide, un 400 a
 * message generique. Aucun message n'ecrit l'exception ni la saisie.
 */
@Controller
public class PartenairesPagesController {

	private static final Logger JOURNAL = Logger.getLogger(PartenairesPagesController.class.getName());

	private static final String V = "plugins/partenaires/";
	private static final String BASE = "/p/partenaires";
	private static final Pattern IDENTIFIANT = Pattern.compile("^[0-9]{1,15}$");

	private static final String MSG_SIREN = "Ce SIREN est déjà enregistré.";
	private static final String MSG_EMAIL_PARTENAIRE = "Cette adresse e-mail est déjà utilisée.";
	private static final String MSG_CLIENT_CONNU = "société déjà connue, une demande d'accès a été envoyée à son manager";
	private static final String MSG_CLIENT_EMAIL = "cette adresse e-mail est déjà utilisée";
	private static final String MSG_INVALIDE = "Données invalides : vérifiez les champs saisis.";
	private static final String MSG_DROIT = "Droit invalide, ou modèle qui deviendrait vide.";

	private final GestionPartenairesContrat contrat;

	public PartenairesPagesController(GestionPartenairesContrat contrat) {
		this.contrat = contrat;
	}

	// ---- liste

	@GetMapping("/liste")
	public String liste(@RequestParam(name = "ok", required = false) String ok, Model model) {
		model.addAttribute("partenaires", contrat.listerPartenaires());
		habiller(model, "Partenaires", ok);
		return V + "liste";
	}

	// ---- partenaire

	@GetMapping("/nouveau")
	public String nouveau(Model model) {
		habiller(model, "Créer un partenaire", null);
		model.addAttribute("saisie", Map.of());
		return V + "partenaire-form";
	}

	@PostMapping("/")
	public ModelAndView creerPartenaire(@RequestParam(name = "siren", required = false) String siren,
			@RequestParam(name = "tva", required = false) String tva,
			@RequestParam(name = "raisonSociale", required = false) String raisonSociale,
			@RequestParam(name = "email", required = false) String email,
			@RequestParam(name = "nom", required = false) String nom) {
		Map<String, String> saisie = new LinkedHashMap<>();
		saisie.put("siren", siren);
		saisie.put("tva", tva);
		saisie.put("raisonSociale", raisonSociale);
		saisie.put("email", email);
		saisie.put("nom", nom);
		ResultatPartenaire r;
		try {
			r = contrat.creerPartenaire(new CreationPartenaire(siren, tva, raisonSociale, email, nom));
		}
		catch (IllegalArgumentException e) {
			return formulaire("partenaire-form", "Créer un partenaire", saisie, MSG_INVALIDE, HttpStatus.BAD_REQUEST,
					null);
		}
		String issue = r == null ? null : r.issue();
		if ("CREE".equals(issue)) {
			return new ModelAndView("redirect:" + BASE + "/liste?ok=partenaire");
		}
		if ("SIREN_DEJA_CONNU".equals(issue)) {
			return formulaire("partenaire-form", "Créer un partenaire", saisie, MSG_SIREN, HttpStatus.CONFLICT, null);
		}
		if ("EMAIL_DEJA_UTILISE".equals(issue)) {
			return formulaire("partenaire-form", "Créer un partenaire", saisie, MSG_EMAIL_PARTENAIRE,
					HttpStatus.CONFLICT, null);
		}
		throw new IllegalStateException("Issue de creation inattendue.");
	}

	// ---- client d'un partenaire (l'identifiant du partenaire vient uniquement du chemin)

	@GetMapping("/{partenaireId}/clients/nouveau")
	public ModelAndView nouveauClient(@PathVariable("partenaireId") String partenaireId,
			HttpServletRequest requete) {
		if (!valide(partenaireId, requete)) {
			return introuvable();
		}
		return formulaire("client-form", "Créer un client", Map.of(), null, HttpStatus.OK,
				Long.parseLong(partenaireId));
	}

	@PostMapping("/{partenaireId}/clients")
	public ModelAndView creerClient(@PathVariable("partenaireId") String partenaireId, HttpServletRequest requete,
			@RequestParam(name = "particulier", required = false) String particulier,
			@RequestParam(name = "siren", required = false) String siren,
			@RequestParam(name = "tva", required = false) String tva,
			@RequestParam(name = "email", required = false) String email,
			@RequestParam(name = "nom", required = false) String nom) {
		if (!valide(partenaireId, requete)) {
			return introuvable();
		}
		long partenaire = Long.parseLong(partenaireId);
		boolean estParticulier = particulier != null && !particulier.isBlank() && !"false".equalsIgnoreCase(particulier);
		Map<String, String> saisie = new LinkedHashMap<>();
		saisie.put("siren", siren);
		saisie.put("tva", tva);
		saisie.put("email", email);
		saisie.put("nom", nom);
		saisie.put("particulier", estParticulier ? "on" : null);
		ResultatCreation r;
		try {
			r = contrat.creerClientPourPartenaire(
					new CreationClient(partenaire, estParticulier, siren, tva, false, email, nom));
		}
		catch (IllegalArgumentException e) {
			return formulaire("client-form", "Créer un client", saisie, MSG_INVALIDE, HttpStatus.BAD_REQUEST,
					partenaire);
		}
		String issue = r == null ? null : r.issue();
		if ("CREE".equals(issue)) {
			return new ModelAndView("redirect:" + BASE + "/liste?ok=client");
		}
		if ("DEJA_CONNUE".equals(issue)) {
			return formulaire("client-form", "Créer un client", saisie, MSG_CLIENT_CONNU, HttpStatus.CONFLICT,
					partenaire);
		}
		if ("EMAIL_DEJA_UTILISE".equals(issue)) {
			return formulaire("client-form", "Créer un client", saisie, MSG_CLIENT_EMAIL, HttpStatus.CONFLICT,
					partenaire);
		}
		throw new IllegalStateException("Issue de creation inattendue.");
	}

	// ---- modele de droits

	@GetMapping("/modele")
	public String modele(@RequestParam(name = "ok", required = false) String ok, Model model) {
		model.addAttribute("droits", droitsTries());
		habiller(model, "Modèle de droits", ok);
		return V + "modele";
	}

	@PostMapping("/modele/ajouter")
	public ModelAndView ajouterDroit(@RequestParam(name = "droit", required = false) String droit) {
		if (!droitValide(droit)) {
			return refusModele();
		}
		Set<String> actuel = new TreeSet<>(contrat.modeleDroits());
		if (actuel.add(droit)) {
			contrat.remplacerModeleDroits(actuel);
		}
		return new ModelAndView("redirect:" + BASE + "/modele?ok=modele");
	}

	@PostMapping("/modele/retirer")
	public ModelAndView retirerDroit(@RequestParam(name = "droit", required = false) String droit) {
		if (!droitValide(droit)) {
			return refusModele();
		}
		Set<String> actuel = new TreeSet<>(contrat.modeleDroits());
		if (!actuel.remove(droit)) {
			// droit absent : idempotent, rien a ecrire
			return new ModelAndView("redirect:" + BASE + "/modele?ok=modele");
		}
		if (actuel.isEmpty()) {
			return refusModele();
		}
		contrat.remplacerModeleDroits(actuel);
		return new ModelAndView("redirect:" + BASE + "/modele?ok=modele");
	}

	// ---- erreurs : textes fixes, jamais le message de l'exception

	@ExceptionHandler(SecurityException.class)
	public ModelAndView refuse() {
		ModelAndView mav = new ModelAndView(V + "refuse");
		mav.setStatus(HttpStatus.FORBIDDEN);
		mav.addObject("titre", "Accès refusé");
		mav.addObject("moduleActif", "partenaires");
		return mav;
	}

	@ExceptionHandler(RuntimeException.class)
	public ModelAndView erreur(RuntimeException e) {
		// detail uniquement dans le journal serveur, jamais dans la reponse
		JOURNAL.log(Level.SEVERE, "Erreur inattendue dans les pages partenaires", e);
		ModelAndView mav = new ModelAndView(V + "erreur");
		mav.setStatus(HttpStatus.INTERNAL_SERVER_ERROR);
		mav.addObject("titre", "Erreur");
		mav.addObject("moduleActif", "partenaires");
		return mav;
	}

	// ---- utilitaires

	/** Identifiant numerique, et aucun parametre de chemin (;) dans l adresse brute (Spring les retire avant la liaison). */
	private List<String> droitsTries() {
		return new ArrayList<>(new TreeSet<>(contrat.modeleDroits()));
	}

	private static boolean valide(String identifiant, HttpServletRequest requete) {
		return identifiant != null && !requete.getRequestURI().contains(";") && IDENTIFIANT.matcher(identifiant).matches();
	}

	private static boolean droitValide(String droit) {
		return droit != null && ManifestePlugin.DROIT_VALIDE.matcher(droit).matches();
	}

	private ModelAndView refusModele() {
		ModelAndView mav = new ModelAndView(V + "modele");
		mav.setStatus(HttpStatus.BAD_REQUEST);
		mav.addObject("droits", droitsTries());
		mav.addObject("titre", "Modèle de droits");
		mav.addObject("moduleActif", "partenaires");
		mav.addObject("erreur", MSG_DROIT);
		return mav;
	}

	private static ModelAndView introuvable() {
		ModelAndView mav = new ModelAndView(V + "erreur");
		mav.setStatus(HttpStatus.NOT_FOUND);
		mav.addObject("titre", "Introuvable");
		mav.addObject("moduleActif", "partenaires");
		mav.addObject("introuvable", true);
		return mav;
	}

	private static ModelAndView formulaire(String vue, String titre, Map<String, String> saisie, String erreur,
			HttpStatus statut, Long partenaireId) {
		ModelAndView mav = new ModelAndView(V + vue);
		mav.setStatus(statut);
		mav.addObject("titre", titre);
		mav.addObject("moduleActif", "partenaires");
		mav.addObject("saisie", saisie);
		if (erreur != null) {
			mav.addObject("erreur", erreur);
		}
		if (partenaireId != null) {
			mav.addObject("partenaireId", partenaireId);
		}
		return mav;
	}

	/** Titre, module actif et message de succes fixe ({@code ok} inconnu ignore). */
	private static void habiller(Model model, String titre, String ok) {
		model.addAttribute("titre", titre);
		model.addAttribute("moduleActif", "partenaires");
		String succes = switch (ok == null ? "" : ok) {
			case "partenaire" -> "Partenaire créé.";
			case "client" -> "Client créé.";
			case "modele" -> "Modèle de droits mis à jour.";
			default -> null;
		};
		if (succes != null) {
			model.addAttribute("succes", succes);
		}
	}

}
