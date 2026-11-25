package com.logicielpourtous.site.plugins.clients;

import com.logicielpourtous.site.sdk.AnnuaireClientsContrat;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.CreationClient;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.FicheClient;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.PorteeAcces;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.ResultatCreation;
import com.logicielpourtous.site.sdk.IdentiteContrat;
import com.logicielpourtous.site.sdk.IdentiteContrat.IdentiteCourante;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
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
 * Pages des clients du partenaire courant (liste, creation, fiche, entree dans l'espace client), servies sous
 * {@code /p/clients} par le noyau (session et CSRF cote noyau).
 *
 * <p>Regles de securite (echec ferme) : le perimetre des droits est la societe du partenaire lui-meme, lue dans
 * l'identite (jamais une valeur de la requete) ; sans societe, sans droit ou sans identite : 403 a page fixe, sans
 * aucune lecture ni ecriture. Une fiche n'est servie que si le client est rattache a CE partenaire ; sinon (inconnue,
 * non cliente, cliente d'un autre partenaire, entree refusee, identifiant mal forme) la reponse est la meme page 404,
 * pour ne rien reveler des clients d'autrui. Toute autre erreur donne un 500 a page fixe ; aucun message n'ecrit
 * l'exception.</p>
 */
@Controller
public class ClientsPagesController {

	private static final Logger JOURNAL = Logger.getLogger(ClientsPagesController.class.getName());

	static final String DROIT_LIRE = "clients.lire";
	static final String DROIT_CREER = "clients.creer";
	static final String DROIT_ENTRER = "clients.entrer";

	private static final String V = "plugins/clients/";
	private static final String BASE = "/p/clients";
	private static final Pattern IDENTIFIANT = Pattern.compile("^[0-9]{1,15}$");

	private static final String MSG_CLIENT_CONNU = "société déjà connue, une demande d'accès a été envoyée à son manager";
	private static final String MSG_CLIENT_EMAIL = "cette adresse e-mail est déjà utilisée";
	private static final String MSG_SIREN_INVALIDE = "SIREN invalide : 9 chiffres avec clé de contrôle correcte";
	private static final String MSG_EMAIL_INVALIDE = "Adresse e-mail invalide (255 caractères au plus, un seul @, sans espace)";
	private static final String MSG_NOM = "Nom obligatoire (255 caractères au plus)";
	private static final String MSG_TVA = "Le numéro de TVA ne correspond pas au SIREN.";
	private static final String MSG_INVALIDE = "Données invalides : vérifiez les champs saisis.";

	private final IdentiteContrat identite;
	private final AnnuaireClientsContrat annuaire;

	public ClientsPagesController(IdentiteContrat identite, AnnuaireClientsContrat annuaire) {
		this.identite = identite;
		this.annuaire = annuaire;
	}

	// ---- liste

	@GetMapping("/liste")
	public String liste(@RequestParam(name = "ok", required = false) String ok, Model model) {
		long societe = exiger(DROIT_LIRE);
		model.addAttribute("clients", annuaire.clientsDuPartenaire(societe));
		habiller(model, "Clients", ok);
		return V + "liste";
	}

	// ---- fiche (le rattachement a CE partenaire est verifie ; sinon la meme page 404)

	@GetMapping("/{societeId}/fiche")
	public ModelAndView fiche(@PathVariable("societeId") String societeId,
			@RequestParam(name = "ok", required = false) String ok, HttpServletRequest requete) {
		long societe = exiger(DROIT_LIRE);
		if (!valide(societeId, requete)) {
			return introuvable();
		}
		Optional<FicheClient> fiche = annuaire.fiche(Long.parseLong(societeId));
		if (fiche == null || fiche.isEmpty() || !Long.valueOf(societe).equals(fiche.get().partenaireSocieteId())) {
			return introuvable();
		}
		ModelAndView mav = new ModelAndView(V + "fiche");
		mav.addObject("fiche", fiche.get());
		habiller(mav, "Fiche client", ok);
		return mav;
	}

	// ---- creation (le partenaire est toujours la societe de l'appelant, jamais un parametre)

	@GetMapping("/nouveau")
	public String nouveau(Model model) {
		exiger(DROIT_CREER);
		habiller(model, "Créer un client", null);
		model.addAttribute("saisie", Map.of());
		model.addAttribute("erreursChamps", Map.of());
		return V + "client-form";
	}

	@PostMapping("/")
	public ModelAndView creer(@RequestParam(name = "particulier", required = false) String particulier,
			@RequestParam(name = "siren", required = false) String siren,
			@RequestParam(name = "tva", required = false) String tva,
			@RequestParam(name = "email", required = false) String email,
			@RequestParam(name = "nom", required = false) String nom) {
		long partenaire = exiger(DROIT_CREER);
		boolean estParticulier = coche(particulier);
		Map<String, String> saisie = new LinkedHashMap<>();
		saisie.put("siren", siren);
		saisie.put("tva", tva);
		saisie.put("email", email);
		saisie.put("nom", nom);
		saisie.put("particulier", estParticulier ? "on" : null);
		Map<String, String> erreurs = new LinkedHashMap<>();
		// SIREN facultatif uniquement pour un particulier ; le noyau refuse les societes etrangeres : jamais lu de la requete
		String sirenNormalise = verifierSiren(siren, !estParticulier, erreurs);
		String tvaNormalisee = verifierTva(tva, sirenNormalise, erreurs);
		verifierEmail(email, erreurs);
		verifierTexte("nom", nom, MSG_NOM, erreurs);
		if (!erreurs.isEmpty()) {
			ModelAndView mav = formulaire(saisie, null, HttpStatus.BAD_REQUEST);
			mav.addObject("erreursChamps", erreurs);
			return mav;
		}
		ResultatCreation r;
		try {
			r = annuaire.creerClient(new CreationClient(partenaire, estParticulier, sirenNormalise, tvaNormalisee,
					false, email, nom));
		}
		catch (IllegalArgumentException e) {
			return formulaire(saisie, messageNoyau(e), HttpStatus.BAD_REQUEST);
		}
		String issue = r == null ? null : r.issue();
		if ("CREE".equals(issue) && r.societeId() != null) {
			return new ModelAndView("redirect:" + BASE + "/" + r.societeId() + "/fiche?ok=cree");
		}
		if ("DEJA_CONNUE".equals(issue)) {
			return formulaire(saisie, MSG_CLIENT_CONNU, HttpStatus.CONFLICT);
		}
		if ("EMAIL_DEJA_UTILISE".equals(issue)) {
			return formulaire(saisie, MSG_CLIENT_EMAIL, HttpStatus.CONFLICT);
		}
		throw new IllegalStateException("Issue de creation inattendue.");
	}

	// ---- entree dans l'espace client (utilisateur et societe viennent de l'identite)

	@PostMapping("/{societeId}/entrer")
	public ModelAndView entrer(@PathVariable("societeId") String societeId, HttpServletRequest requete) {
		exiger(DROIT_ENTRER);
		if (!valide(societeId, requete)) {
			return introuvable();
		}
		Long userId = identite.courante().userId();
		PorteeAcces portee;
		try {
			portee = annuaire.entrerEspaceClient(userId, Long.parseLong(societeId));
		}
		catch (SecurityException e) {
			// meme reponse qu'un client inconnu : aucun detail sur le refus
			return introuvable();
		}
		ModelAndView mav = new ModelAndView(V + "entree");
		mav.addObject("societeId", portee.societeId());
		mav.addObject("droits", List.copyOf(new TreeSet<>(portee.droitsEffectifs())));
		habiller(mav, "Espace client", null);
		return mav;
	}

	// ---- erreurs : textes fixes, jamais le message de l'exception

	@ExceptionHandler(SecurityException.class)
	public ModelAndView refuse() {
		ModelAndView mav = new ModelAndView(V + "refuse");
		mav.setStatus(HttpStatus.FORBIDDEN);
		mav.addObject("titre", "Accès refusé");
		mav.addObject("moduleActif", "clients");
		return mav;
	}

	@ExceptionHandler(RuntimeException.class)
	public ModelAndView erreur(RuntimeException e) {
		// detail uniquement dans le journal serveur, jamais dans la reponse
		JOURNAL.log(Level.SEVERE, "Erreur inattendue dans les pages clients", e);
		ModelAndView mav = new ModelAndView(V + "erreur");
		mav.setStatus(HttpStatus.INTERNAL_SERVER_ERROR);
		mav.addObject("titre", "Erreur");
		mav.addObject("moduleActif", "clients");
		return mav;
	}

	// ---- utilitaires

	/** Societe du partenaire courant si elle existe et si son utilisateur a le droit ; vide sinon (403). */
	private Optional<Long> societeAutorisee(String droit) {
		IdentiteCourante moi;
		try {
			moi = identite.courante();
		}
		catch (IllegalStateException e) {
			return Optional.empty();
		}
		if (moi == null || moi.userId() == null || moi.societeId() == null) {
			return Optional.empty();
		}
		return annuaire.autorise(moi.userId(), moi.societeId(), droit) ? Optional.of(moi.societeId())
				: Optional.empty();
	}

	/** Societe de l'appelant, ou {@link SecurityException} (403) : aucune lecture ni ecriture n'a eu lieu avant. */
	private long exiger(String droit) {
		return societeAutorisee(droit).orElseThrow(() -> new SecurityException("Droit manquant : " + droit));
	}

	/** Identifiant numerique, et aucun parametre de chemin (;) dans l'adresse brute (Spring les retire avant la liaison). */
	private static boolean valide(String identifiant, HttpServletRequest requete) {
		return identifiant != null && !requete.getRequestURI().contains(";") && IDENTIFIANT.matcher(identifiant).matches();
	}

	private static boolean coche(String valeur) {
		return valeur != null && !valeur.isBlank() && !"false".equalsIgnoreCase(valeur);
	}

	private static ModelAndView introuvable() {
		ModelAndView mav = new ModelAndView(V + "introuvable");
		mav.setStatus(HttpStatus.NOT_FOUND);
		mav.addObject("titre", "Introuvable");
		mav.addObject("moduleActif", "clients");
		return mav;
	}

	private static ModelAndView formulaire(Map<String, String> saisie, String erreur, HttpStatus statut) {
		ModelAndView mav = new ModelAndView(V + "client-form");
		mav.setStatus(statut);
		mav.addObject("titre", "Créer un client");
		mav.addObject("moduleActif", "clients");
		mav.addObject("saisie", saisie);
		mav.addObject("erreursChamps", Map.of());
		if (erreur != null) {
			mav.addObject("erreur", erreur);
		}
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

	/** Titre, module actif et message de succes fixe ({@code ok} inconnu ignore). */
	private static void habiller(Model model, String titre, String ok) {
		model.addAttribute("titre", titre);
		model.addAttribute("moduleActif", "clients");
		String succes = succes(ok);
		if (succes != null) {
			model.addAttribute("succes", succes);
		}
	}

	private static void habiller(ModelAndView mav, String titre, String ok) {
		mav.addObject("titre", titre);
		mav.addObject("moduleActif", "clients");
		String succes = succes(ok);
		if (succes != null) {
			mav.addObject("succes", succes);
		}
	}

	private static String succes(String ok) {
		return "cree".equals(ok) ? "Client créé." : null;
	}

}
