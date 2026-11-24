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
	private static final String MSG_SIREN_INVALIDE = "SIREN invalide : 9 chiffres avec clé de contrôle correcte";
	private static final String MSG_EMAIL_INVALIDE = "Adresse e-mail invalide (255 caractères au plus, un seul @, sans espace)";
	private static final String MSG_NOM = "Nom obligatoire (255 caractères au plus)";
	private static final String MSG_RAISON = "Raison sociale obligatoire (255 caractères au plus)";
	private static final String MSG_TVA = "Le numéro de TVA ne correspond pas au SIREN.";
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
		model.addAttribute("erreursChamps", Map.of());
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
		Map<String, String> erreurs = new LinkedHashMap<>();
		String sirenNormalise = verifierSiren(siren, true, erreurs);
		String tvaNormalisee = verifierTva(tva, sirenNormalise, erreurs);
		verifierTexte("raisonSociale", raisonSociale, MSG_RAISON, erreurs);
		verifierEmail(email, erreurs);
		verifierTexte("nom", nom, MSG_NOM, erreurs);
		if (!erreurs.isEmpty()) {
			return refus("partenaire-form", "Créer un partenaire", saisie, erreurs, null);
		}
		ResultatPartenaire r;
		try {
			r = contrat.creerPartenaire(new CreationPartenaire(sirenNormalise, tvaNormalisee, raisonSociale, email, nom));
		}
		catch (IllegalArgumentException e) {
			return formulaire("partenaire-form", "Créer un partenaire", saisie, messageNoyau(e), HttpStatus.BAD_REQUEST,
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

	@GetMapping("/{partenaireId}/clients")
	public ModelAndView clients(@PathVariable("partenaireId") String partenaireId, HttpServletRequest requete) {
		if (!valide(partenaireId, requete)) {
			return introuvable();
		}
		long id = Long.parseLong(partenaireId);
		List<GestionPartenairesContrat.ClientResume> liste;
		try {
			liste = contrat.listerClientsDuPartenaire(id);
		}
		catch (IllegalArgumentException e) {
			JOURNAL.log(Level.WARNING, "Partenaire introuvable pour la liste des clients : {0}", e.getMessage());
			return introuvable();
		}
		ModelAndView mav = new ModelAndView(V + "clients");
		mav.addObject("clients", liste);
		mav.addObject("partenaireId", id);
		mav.addObject("titre", "Clients du partenaire");
		mav.addObject("moduleActif", "partenaires");
		return mav;
	}

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
		Map<String, String> erreurs = new LinkedHashMap<>();
		String sirenNormalise = verifierSiren(siren, !estParticulier, erreurs);
		String tvaNormalisee = verifierTva(tva, sirenNormalise, erreurs);
		verifierEmail(email, erreurs);
		verifierTexte("nom", nom, MSG_NOM, erreurs);
		if (!erreurs.isEmpty()) {
			return refus("client-form", "Créer un client", saisie, erreurs, partenaire);
		}
		ResultatCreation r;
		try {
			r = contrat.creerClientPourPartenaire(
					new CreationClient(partenaire, estParticulier, sirenNormalise, tvaNormalisee, false, email, nom));
		}
		catch (IllegalArgumentException e) {
			return formulaire("client-form", "Créer un client", saisie, messageNoyau(e), HttpStatus.BAD_REQUEST,
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
	/** Un champ de formulaire laisse vide arrive en chaine vide : le contrat attend null. */
	private static String videEnNull(String valeur) {
		return valeur == null || valeur.isBlank() ? null : valeur;
	}

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

	private static ModelAndView refus(String vue, String titre, Map<String, String> saisie,
			Map<String, String> erreurs, Long partenaireId) {
		ModelAndView mav = formulaire(vue, titre, saisie, null, HttpStatus.BAD_REQUEST, partenaireId);
		mav.addObject("erreursChamps", erreurs);
		return mav;
	}

	/** SIREN : 9 chiffres (espaces ignores) et cle de Luhn ; vide accepte (null) si non requis. */
	private static String verifierSiren(String siren, boolean requis, Map<String, String> erreurs) {
		String s = siren == null ? "" : siren.replaceAll("[\\s.\\-]", "");
		if (s.isEmpty() && !requis) {
			return null;
		}
		if (!s.matches("[0-9]{9}") || !luhn(s)) {
			erreurs.put("siren", MSG_SIREN_INVALIDE);
			return null;
		}
		return s;
	}

	/** TVA normalisee (majuscules, sans espace, point ni tiret) ; vide = null (calculee par le noyau) ; francaise : doit correspondre au SIREN. */
	private static String verifierTva(String tva, String siren, Map<String, String> erreurs) {
		String t = tva == null ? "" : tva.replaceAll("[\\s.\\-]", "").toUpperCase(java.util.Locale.ROOT);
		if (t.isEmpty()) {
			return null;
		}
		if (siren != null) {
			long cle = (12 + 3 * (Long.parseLong(siren) % 97)) % 97;
			if (!t.equals("FR" + String.format("%02d", cle) + siren)) {
				erreurs.put("tva", MSG_TVA);
			}
		}
		return t;
	}

	/** Message du noyau s'il est court et non vide (textes fixes), sinon texte generique ; journalise en WARNING. */
	private static String messageNoyau(IllegalArgumentException e) {
		JOURNAL.log(Level.WARNING, "Demande refusee par le noyau : {0}", e.getMessage());
		String m = e.getMessage();
		return m == null || m.isBlank() || m.length() > 200 ? MSG_INVALIDE : m;
	}

	private static boolean luhn(String chiffres) {
		int somme = 0;
		for (int i = 0; i < chiffres.length(); i++) {
			int d = chiffres.charAt(chiffres.length() - 1 - i) - 48;
			if (i % 2 == 1) {
				d *= 2;
				if (d > 9) {
					d -= 9;
				}
			}
			somme += d;
		}
		return somme % 10 == 0;
	}

	private static void verifierTexte(String champ, String valeur, String message, Map<String, String> erreurs) {
		if (valeur == null || valeur.isBlank() || valeur.length() > 255) {
			erreurs.put(champ, message);
		}
	}

	/** E-mail : non vide, 255 au plus, un seul @ avec du texte de chaque cote, sans espace. */
	private static void verifierEmail(String email, Map<String, String> erreurs) {
		int a = email == null ? -1 : email.indexOf(64);
		boolean ok = email != null && !email.isBlank() && email.length() <= 255 && a > 0 && a < email.length() - 1
				&& email.indexOf(64, a + 1) < 0 && email.chars().noneMatch(Character::isWhitespace);
		if (!ok) {
			erreurs.put("email", MSG_EMAIL_INVALIDE);
		}
	}

	private static ModelAndView formulaire(String vue, String titre, Map<String, String> saisie, String erreur,
			HttpStatus statut, Long partenaireId) {
		ModelAndView mav = new ModelAndView(V + vue);
		mav.setStatus(statut);
		mav.addObject("titre", titre);
		mav.addObject("moduleActif", "partenaires");
		mav.addObject("saisie", saisie);
		mav.addObject("erreursChamps", Map.of());
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
