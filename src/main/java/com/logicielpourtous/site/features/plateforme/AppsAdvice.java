package com.logicielpourtous.site.features.plateforme;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.logicielpourtous.site.features.commun.ServerRole;
import com.logicielpourtous.site.features.commun.ServerRoleProperties;
import com.logicielpourtous.site.features.plateforme.NavigationService.Tuile;
import com.logicielpourtous.site.sdk.ManifestePlugin;

/**
 * Expose à tous les écrans l'attribut {@code apps}, utilisé par le menu d'applications du socle. L'objet est PARESSEUX :
 * rien n'est lu tant qu'un gabarit n'appelle pas {@code apps.tuiles()} (aucun coût pour les écrans qui ne l'utilisent
 * pas), puis la lecture n'a lieu qu'une fois par requête. Toute erreur donne une liste vide (échec fermé).
 */
@ControllerAdvice
@ConditionalOnProperty(name = "app.plateforme.actif", havingValue = "true")
public class AppsAdvice {

	private final NavigationService navigation;

	/** Ce serveur est le Maître (même lecture de {@code app.role} que {@link SurServeurMaitre}). */
	private final boolean maitre;

	public AppsAdvice(NavigationService navigation, Environment environnement) {
		this.navigation = navigation;
		ServerRoleProperties role = new ServerRoleProperties();
		role.setRole(environnement.getProperty("app.role"));
		this.maitre = role.getRole() == ServerRole.MASTER;
	}

	@ModelAttribute("apps")
	public Apps apps() {
		return new Apps(navigation, maitre);
	}

	/**
	 * Tuile prête à afficher : {@code chemin()} est l'adresse serveur COMPLÈTE (même nom que les fragments du socle ;
	 * attention, {@code NavigationService.Tuile#chemin()} est au contraire le chemin brut du manifeste),
	 * {@code icone()} la classe Bootstrap Icons complète, {@code couleur()} un nom de la liste fermée.
	 */
	public record TuileApp(String libelle, String icone, String couleur, String url) {

		public String chemin() {
			return url;
		}

		static TuileApp depuis(Tuile t) {
			String icone = t.icone() == null ? "" : t.icone().startsWith("bi-") ? t.icone().substring(3) : t.icone();
			if (!ManifestePlugin.ICONE_VALIDE.matcher(icone).matches()) {
				icone = "app";
			}
			String couleur = t.couleur() != null && ManifestePlugin.COULEURS_TUILE.contains(t.couleur())
					? t.couleur() : "primaire";
			return new TuileApp(t.libelle(), "bi-" + icone, couleur, t.url());
		}
	}

	/** Tuiles de l'utilisateur courant, lues au premier appel de {@link #tuiles()}. */
	public static final class Apps {

		private static final Logger log = LoggerFactory.getLogger(Apps.class);

		/** Écran du noyau (pas d'un plugin) : réglage du thème du serveur, réservé à ROLE_ADMIN. */
		private static final TuileApp TUILE_APPARENCE = new TuileApp("Apparence", "bi-palette", "primaire",
				"/admin/apparence");

		/** Écran du noyau : gestion des plugins, réservé à ROLE_ADMIN et au serveur Maître. */
		private static final TuileApp TUILE_PLUGINS = new TuileApp("Plugins", "bi-puzzle", "primaire",
				"/admin/plugins");

		private final NavigationService navigation;

		private final boolean maitre;

		private List<TuileApp> tuiles;

		Apps(NavigationService navigation, boolean maitre) {
			this.navigation = navigation;
			this.maitre = maitre;
		}

		public synchronized List<TuileApp> tuiles() {
			if (tuiles == null) {
				List<TuileApp> liste = new ArrayList<>();
				try {
					navigation.tuilesAutorisees(null).stream().filter(t -> t.url() != null).map(TuileApp::depuis)
							.forEach(liste::add);
				}
				catch (RuntimeException e) {
					log.warn("Accueil : lecture des applications en échec, liste vide", e);
					liste.clear();
				}
				if (adminDuServeur()) {
					liste.add(TUILE_APPARENCE);
					if (maitre) {
						liste.add(TUILE_PLUGINS);
					}
				}
				tuiles = List.copyOf(liste);
			}
			return tuiles;
		}

		/** Même condition que la règle de sécurité de {@code /admin/**} : rôle ROLE_ADMIN. */
		private static boolean adminDuServeur() {
			Authentication auth = SecurityContextHolder.getContext().getAuthentication();
			return auth != null && auth.isAuthenticated() && auth.getAuthorities().stream()
					.anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
		}

	}

}
