package com.logicielpourtous.site.features.plateforme;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

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

	public AppsAdvice(NavigationService navigation) {
		this.navigation = navigation;
	}

	@ModelAttribute("apps")
	public Apps apps() {
		return new Apps(navigation);
	}

	/**
	 * Tuile prête à afficher : {@code chemin()} est l'adresse serveur complète (même nom que les fragments du socle),
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

		private final NavigationService navigation;

		private List<TuileApp> tuiles;

		Apps(NavigationService navigation) {
			this.navigation = navigation;
		}

		public synchronized List<TuileApp> tuiles() {
			if (tuiles == null) {
				try {
					tuiles = navigation.tuilesAutorisees(null).stream().filter(t -> t.url() != null)
							.map(TuileApp::depuis).toList();
				}
				catch (RuntimeException e) {
					log.warn("Accueil : lecture des applications en échec, liste vide", e);
					tuiles = List.of();
				}
			}
			return tuiles;
		}

	}

}
