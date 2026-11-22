package com.logicielpourtous.site.features.plateforme;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.logicielpourtous.site.sdk.ExtensionUi;
import com.logicielpourtous.site.sdk.IdentiteContrat;
import com.logicielpourtous.site.sdk.IdentiteContrat.IdentiteCourante;
import com.logicielpourtous.site.sdk.ManifestePlugin;

/**
 * Boutons et onglets que les plugins ACTIFS contribuent à un emplacement d'écran ({@link ExtensionUi}), filtrés par
 * les droits de l'utilisateur courant sur la société.
 *
 * <p>SÉCURITÉ : le filtre par droit appliqué ici est un confort d'affichage, PAS une protection. La route du plugin
 * derrière chaque adresse doit elle-même contrôler les droits de l'utilisateur et l'appartenance de l'entité demandée.
 *
 * <p>Échec fermé : identité absente, société inconnue, droit non accordé, plugin absent ou en maintenance, manifeste
 * illisible, adresse non sûre ou identifiant d'entité non numérique omettent l'élément, jamais l'écran. Les plugins
 * sont relus à CHAQUE appel (aucun cache) pour suivre les mises à jour à chaud. Les journaux ne reprennent pas le
 * texte du manifeste au-delà du nom du plugin.
 */
@Service
@ConditionalOnProperty(name = "app.plateforme.actif", havingValue = "true")
public class EmplacementsService {

	private static final Logger log = LoggerFactory.getLogger(EmplacementsService.class);

	private static final Pattern ID_ENTITE = Pattern.compile("[0-9]{1,15}");

	private static final String MARQUEUR_ID = "{id}";

	/** Élément prêt à afficher : {@code url} est validée ({@code /p/<plugin>/…}), {@code libelle} est du texte. */
	public record ElementAffiche(String libelle, String url, String plugin, int ordre) {
	}

	private final PluginManager manager;

	private final ControleDroits droits;

	private final IdentiteContrat identite;

	public EmplacementsService(PluginManager manager, ControleDroits droits, IdentiteContrat identite) {
		this.manager = manager;
		this.droits = droits;
		this.identite = identite;
	}

	/**
	 * @param emplacement nom de l'emplacement
	 * @param societeId société ; {@code null} : société de la session
	 * @param idEntite identifiant de l'entité de l'écran hôte ; remplace {@code {id}} s'il est numérique (1 à 15
	 *            chiffres), sinon les éléments dont le chemin contient {@code {id}} sont omis
	 * @return les éléments autorisés, triés par ordre, puis plugin, puis ordre de déclaration
	 */
	public List<ElementAffiche> elements(String emplacement, Long societeId, String idEntite) {
		if (emplacement == null) {
			return List.of();
		}
		Contexte contexte = contexte(societeId);
		if (contexte == null) {
			return List.of();
		}
		boolean idValide = idEntite != null && ID_ENTITE.matcher(idEntite).matches();
		List<ElementAffiche> resultat = new ArrayList<>();
		for (Map.Entry<String, PluginGeneration> actif : pluginsActifs().entrySet()) {
			ManifestePlugin m = manifeste(actif.getKey(), actif.getValue());
			if (m == null) {
				continue;
			}
			for (ExtensionUi e : m.extensions()) {
				try {
					if (e != null && emplacement.equals(e.emplacement()) && contexte.autorise(e.droit())) {
						ElementAffiche element = element(m.nom(), e, idValide ? idEntite : null);
						if (element != null) {
							resultat.add(element);
						}
					}
				}
				catch (RuntimeException ex) {
					log.warn("Emplacements : élément du plugin '{}' ignoré", m.nom());
				}
			}
		}
		// Tri stable : l'ordre de parcours (plugin, puis déclaration) départage les ordres égaux.
		resultat.sort(Comparator.comparingInt(ElementAffiche::ordre));
		return List.copyOf(resultat);
	}

	/** Élément affichable, ou {@code null} si le libellé ou l'adresse n'est pas sûr(e) ou si {@code {id}} manque. */
	private static ElementAffiche element(String plugin, ExtensionUi e, String idNumerique) {
		String libelle = e.libelle();
		String chemin = e.chemin();
		if (libelle == null || libelle.isBlank() || chemin == null || !chemin.startsWith("/")) {
			return null;
		}
		if (chemin.contains(MARQUEUR_ID)) {
			if (idNumerique == null) {
				return null;
			}
			chemin = chemin.replace(MARQUEUR_ID, idNumerique);
		}
		String url = "/p/" + plugin + chemin;
		if (!urlSure(url, plugin)) {
			log.warn("Emplacements : chemin refusé pour un élément du plugin '{}', ignoré", plugin);
			return null;
		}
		return new ElementAffiche(libelle, url, plugin, e.ordre());
	}

	/** Reste sous {@code /p/<plugin>/} : ni {@code //}, ni {@code ..}, ni antislash, espace, contrôle, {@code %}, {@code #}. */
	private static boolean urlSure(String url, String plugin) {
		String prefixe = "/p/" + plugin;
		if (!url.startsWith(prefixe + "/") || url.indexOf("//", prefixe.length()) >= 0 || url.contains("..")
				|| url.indexOf('\\') >= 0 || url.indexOf('%') >= 0 || url.indexOf('#') >= 0
				|| url.indexOf('{') >= 0 || url.indexOf('}') >= 0) {
			return false;
		}
		for (int i = 0; i < url.length(); i++) {
			char c = url.charAt(i);
			if (Character.isWhitespace(c) || Character.isISOControl(c)) {
				return false;
			}
		}
		return true;
	}

	/** Plugins actifs ET pas en maintenance, triés par nom (relus à chaque appel). */
	private Map<String, PluginGeneration> pluginsActifs() {
		Set<String> enService = manager.etats().stream().filter(e -> e.etat() == EtatPlugin.Etat.ACTIF)
				.map(EtatPlugin::nom).collect(Collectors.toSet());
		Map<String, PluginGeneration> tries = new TreeMap<>();
		manager.actifs().forEach((nom, gen) -> {
			if (enService.contains(nom)) {
				tries.put(nom, gen);
			}
		});
		return tries;
	}

	/** Manifeste de la génération, ou {@code null} (plugin ignoré) s'il manque, est illisible ou ne correspond pas. */
	private static ManifestePlugin manifeste(String nom, PluginGeneration gen) {
		try {
			ManifestePlugin m = gen == null ? null : gen.manifeste();
			if (m == null || !nom.equals(m.nom()) || m.extensions() == null) {
				log.warn("Emplacements : manifeste absent ou incohérent pour le plugin '{}', ignoré", nom);
				return null;
			}
			return m;
		}
		catch (RuntimeException e) {
			log.warn("Emplacements : manifeste illisible pour le plugin '{}', ignoré", nom);
			return null;
		}
	}

	/** Utilisateur et société de la session ; {@code null} si rien ne peut être autorisé. */
	private Contexte contexte(Long societeDemandee) {
		IdentiteCourante courante;
		try {
			courante = identite.courante();
		}
		catch (RuntimeException e) {
			return null;
		}
		if (courante == null || courante.userId() == null) {
			return null;
		}
		Long societe = societeDemandee != null ? societeDemandee : courante.societeId();
		return societe == null ? null : new Contexte(courante.userId(), societe);
	}

	/** Mémorise les décisions de droit le temps d'un appel. */
	private final class Contexte {

		private final Long userId;

		private final Long societeId;

		private final Map<String, Boolean> decisions = new HashMap<>();

		Contexte(Long userId, Long societeId) {
			this.userId = userId;
			this.societeId = societeId;
		}

		boolean autorise(String droit) {
			if (droit == null) {
				return false;
			}
			Boolean connu = decisions.get(droit);
			if (connu != null) {
				return connu;
			}
			boolean decision;
			try {
				decision = droits.autorise(userId, societeId, droit);
			}
			catch (RuntimeException e) {
				log.warn("Emplacements : contrôle d'un droit en échec, élément refusé");
				decision = false;
			}
			decisions.put(droit, decision);
			return decision;
		}

	}

}
