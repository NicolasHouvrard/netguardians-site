package com.logicielpourtous.site.features.plateforme;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import com.logicielpourtous.site.features.plateforme.DistributionController.EtatServeur;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Écran du noyau « Plugins » ({@code /admin/plugins}) : publier un jar déjà signé dans le dépôt du Maître, voir le
 * dépôt, les plugins installés sur le Maître et l'état rapporté par les serveurs annexes, désinstaller un plugin du
 * Maître. N'existe que sur un Maître ({@code app.plateforme.actif=true} et {@code app.role=MASTER}) ; réservé à
 * {@code ROLE_ADMIN} (chaîne de sécurité web + garde de méthode), CSRF exigé sur les POST.
 * <ul>
 * <li>L'écran ne signe rien et ne connaît aucune clé privée : il transmet un jar et son {@code .sig} à
 * {@link DepotPlugins#publier}, qui vérifie la signature. Le nom de fichier annoncé par le navigateur n'est jamais
 * utilisé.</li>
 * <li>Les messages sont des textes fixes, sauf le message d'un refus d'invalidité du dépôt (déjà nettoyé, sans chemin de
 * fichier). Le message de succès vient d'une liste fermée, jamais du paramètre reçu. Tout texte dynamique est affiché
 * par {@code th:text}.</li>
 * <li>Limite d'envoi : 10 Mo (multipart global). Au-delà, le comportement exact (refus par le filtre CSRF ou
 * par Tomcat, réponse obtenue) n'est ni garanti ni testé : ce contrôleur n'affiche pas de message dédié.</li>
 * </ul>
 */
@Controller
@RequestMapping("/admin/plugins")
@PreAuthorize("hasRole('ADMIN')")
@ConditionalOnProperty(name = "app.plateforme.actif", havingValue = "true")
@Conditional(SurServeurMaitre.class)
public class PluginsEcranController {

	private static final Logger log = LoggerFactory.getLogger(PluginsEcranController.class);

	private static final String VUE = "admin/plugins";

	/** Nom de plugin accepté par la désinstallation. */
	private static final Pattern NOM_VALIDE = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

	/** Messages de succès : liste fermée, indexée par la valeur du paramètre {@code ok}. */
	private static final Map<String, String> MESSAGES_OK = Map.of("publie", "Plugin publié dans le dépôt du Maître.",
			"identique", "Plugin déjà publié (identique) : rien à faire.", "desinstalle",
			"Plugin retiré du Maître jusqu'au prochain redémarrage (les serveurs annexes ne sont pas touchés). Aucun retrait durable depuis cet écran : voir la documentation de publication.");

	private final DepotPlugins depot;

	private final PluginManager manager;

	private final LectureEtatsServeurs lecture;

	public PluginsEcranController(DepotPlugins depot, PluginManager manager, LectureEtatsServeurs lecture) {
		this.depot = depot;
		this.manager = manager;
		this.lecture = lecture;
	}

	@GetMapping
	public String page(@RequestParam(name = "ok", required = false) String ok, Model model) {
		String message = ok == null ? null : MESSAGES_OK.get(ok);
		if (message != null) {
			model.addAttribute("message", message);
		}
		return remplir(model);
	}

	@PostMapping(path = "/publier", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public String publier(@RequestParam(name = "jar", required = false) MultipartFile jar,
			@RequestParam(name = "sig", required = false) MultipartFile sig, Model model,
			HttpServletResponse reponse) {
		if (jar == null || sig == null || jar.isEmpty() || sig.isEmpty()) {
			return refuser(model, reponse, 400, "jar et signature obligatoires");
		}
		try (InputStream j = jar.getInputStream(); InputStream s = sig.getInputStream()) {
			List<EntreeCatalogue> avant = depot.catalogue();
			EntreeCatalogue publiee = depot.publier(j, s);
			return "redirect:/admin/plugins?ok=" + (avant.contains(publiee) ? "identique" : "publie");
		}
		catch (DepotPlugins.DepotRefuse e) {
			return switch (e.motif()) {
				case INVALIDE -> refuser(model, reponse, 400, e.getMessage());
				case CONFLIT -> refuser(model, reponse, 409,
						"version déjà publiée avec un contenu différent : publier un numéro plus haut");
				// message du dépôt : déjà nettoyé et porte la limite réellement configurée
				case TROP_VOLUMINEUX -> refuser(model, reponse, 413, e.getMessage());
			};
		}
		catch (IOException | RuntimeException e) {
			log.error("Écran plugins : publication impossible", e);
			return refuser(model, reponse, 500, "dépôt indisponible (voir le journal du Maître)");
		}
	}

	@PostMapping("/{nom}/desinstaller")
	public String desinstaller(@PathVariable("nom") String nom, Model model, HttpServletResponse reponse) {
		if (!NOM_VALIDE.matcher(nom).matches()) {
			return refuser(model, reponse, 400, "nom de plugin invalide");
		}
		if (!manager.desinstaller(nom)) {
			return refuser(model, reponse, 404, "Plugin inconnu : rien à désinstaller");
		}
		return "redirect:/admin/plugins?ok=desinstalle";
	}

	private String refuser(Model model, HttpServletResponse reponse, int statut, String message) {
		reponse.setStatus(statut);
		model.addAttribute("erreur", message);
		return remplir(model);
	}

	/** Données de la page ; une lecture en échec laisse la section vide avec un message fixe (jamais de trace). */
	private String remplir(Model model) {
		model.addAttribute("catalogue", lire("dépôt", depot::catalogue, model));
		model.addAttribute("installes", lire("plugins installés", manager::etats, model));
		List<EtatServeur> serveurs = lire("serveurs annexes",
				() -> lecture.derniers(DistributionController.LISTE_DEFAUT), model);
		model.addAttribute("serveurs", serveurs);
		model.addAttribute("titre", "Plugins");
		model.addAttribute("moduleActif", "plugins");
		model.addAttribute("fil",
				List.of(Map.of("libelle", "Administration", "url", "/admin"), Map.of("libelle", "Plugins")));
		return VUE;
	}

	private <T> List<T> lire(String quoi, Supplier<List<T>> source, Model model) {
		try {
			return source.get();
		}
		catch (RuntimeException e) {
			log.warn("Écran plugins : lecture de {} en échec", quoi, e);
			model.addAttribute("avertissement", "Une partie des informations est indisponible (voir le journal).");
			return List.of();
		}
	}

}
