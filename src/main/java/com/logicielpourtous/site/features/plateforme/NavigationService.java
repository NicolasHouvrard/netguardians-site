package com.logicielpourtous.site.features.plateforme;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.logicielpourtous.site.sdk.BlocFiche;
import com.logicielpourtous.site.sdk.EntreeNavigation;
import com.logicielpourtous.site.sdk.IdentiteContrat;
import com.logicielpourtous.site.sdk.IdentiteContrat.IdentiteCourante;
import com.logicielpourtous.site.sdk.ManifestePlugin;
import com.logicielpourtous.site.sdk.ZoneNavigation;

/**
 * Tuiles de navigation et blocs de fiche déclarés par les plugins ACTIFS, filtrés par les droits de l'utilisateur
 * courant sur la société demandée.
 *
 * <p>Sécurité : l'utilisateur vient toujours de {@link IdentiteContrat#courante()}, jamais de la requête. Tout est
 * refusé par défaut (échec fermé) : identité absente, société inconnue, droit non accordé, plugin absent ou en
 * maintenance, manifeste illisible ou incohérent, donnent une liste vide ou l'omission de l'entrée, jamais une erreur
 * ni une tuile. Les plugins actifs sont relus à CHAQUE appel (aucun cache) pour suivre les mises à jour à chaud.
 *
 * <p>Décision : les entrées de zone {@link ZoneNavigation#PUBLIQUE} sont rendues sans contrôle de droit (elles sont
 * publiques par définition ; seul l'accès à l'endpoint exige d'être authentifié). Toutes les autres zones, et tous les
 * blocs de fiche, exigent le droit déclaré.
 */
@Service
@ConditionalOnProperty(name = "app.plateforme.actif", havingValue = "true")
public class NavigationService {

	private static final Logger log = LoggerFactory.getLogger(NavigationService.class);

	/** Tuile renvoyée au front. */
	public record Tuile(String plugin, String libelle, String icone, String chemin, ZoneNavigation zone,
			String version) {
	}

	/** Bloc de fiche client renvoyé au front. */
	public record BlocAutorise(String plugin, String id, String libelle, String chemin, String version) {
	}

	private final PluginManager manager;

	private final ControleDroits droits;

	private final IdentiteContrat identite;

	private final ControleCapacites capacites;

	public NavigationService(PluginManager manager, ControleDroits droits, IdentiteContrat identite,
			ControleCapacites capacites) {
		this.manager = manager;
		this.droits = droits;
		this.identite = identite;
		this.capacites = capacites;
	}

	/**
	 * Tuiles de toutes les zones auxquelles la société qualifie, la zone étant décidée ICI (le front ne la devine
	 * pas) : capacité PARTENAIRE vers la zone PARTENAIRE, capacité CLIENT vers la zone CLIENT (une société qui a les
	 * deux reçoit les deux ; jamais PUBLIQUE). Les entrées ADMIN ne sont ajoutées que pour l'admin du serveur maître,
	 * sans contrôle de droit sur une société (même sans société). Chaque tuile porte sa zone. Échec fermé : sans société, sans
	 * capacité ou sans droit sur la société (appartenance vérifiée par le contrôle des droits, y compris pour un
	 * {@code societeId} demandé), liste vide ; une société cliente seule ne reçoit jamais de tuile PARTENAIRE.
	 *
	 * @param societeId société ; {@code null} : société de la session
	 */
	public List<Tuile> tuilesAutorisees(Long societeId) {
		List<Tuile> resultat = new ArrayList<>(tuilesSociete(societeId));
		if (adminDuMaitre()) {
			resultat.addAll(tuilesAdmin());
		}
		return List.copyOf(resultat);
	}

	/**
	 * Admin du serveur maître : identité valide, rôle {@code ROLE_ADMIN} et serveur {@code MASTER}. Même prédicat que
	 * celui du contrat de gestion des partenaires (dupliqué ici car le module plateforme ne dépend que de commun ; les
	 * deux sont testés sur les mêmes cas). Échec fermé sur toute erreur de lecture de l'identité.
	 */
	private boolean adminDuMaitre() {
		try {
			IdentiteCourante moi = identite.courante();
			return moi != null && moi.userId() != null && moi.roles() != null && moi.roles().contains("ROLE_ADMIN")
					&& "MASTER".equals(moi.roleServeur());
		}
		catch (RuntimeException e) {
			return false;
		}
	}

	/** Entrées ADMIN des plugins actifs, sans droit sur une société : l'appelant a déjà vérifié l'admin du maître. */
	private List<Tuile> tuilesAdmin() {
		List<Tuile> resultat = new ArrayList<>();
		for (Map.Entry<String, PluginGeneration> actif : pluginsActifs().entrySet()) {
			ManifestePlugin m = manifeste(actif.getKey(), actif.getValue());
			if (m == null) {
				continue;
			}
			for (EntreeNavigation e : m.entrees()) {
				if (e.zone() == ZoneNavigation.ADMIN) {
					resultat.add(new Tuile(m.nom(), e.libelle(), e.icone(), e.chemin(), e.zone(),
							actif.getValue().version));
				}
			}
		}
		return List.copyOf(resultat);
	}

	/** Tuiles PARTENAIRE / CLIENT d'après les capacités de la société (jamais ADMIN ni PUBLIQUE). */
	private List<Tuile> tuilesSociete(Long societeId) {
		Contexte contexte = contexte(societeId, true);
		if (contexte == null || contexte.societeId == null) {
			return List.of();
		}
		Set<ZoneNavigation> zones = EnumSet.noneOf(ZoneNavigation.class);
		if (aCapacite(contexte.societeId, "PARTENAIRE")) {
			zones.add(ZoneNavigation.PARTENAIRE);
		}
		if (aCapacite(contexte.societeId, "CLIENT")) {
			zones.add(ZoneNavigation.CLIENT);
		}
		if (zones.isEmpty()) {
			return List.of();
		}
		return collecter(zones, contexte);
	}

	private boolean aCapacite(Long societeId, String capacite) {
		try {
			return capacites.aCapacite(societeId, capacite);
		}
		catch (RuntimeException e) {
			log.warn("Navigation : lecture de la capacité '{}' en échec, refusée", capacite, e);
			return false;
		}
	}

	/**
	 * @param zone zone demandée (obligatoire)
	 * @param societeId société ; {@code null} : société de la session
	 * @return les tuiles autorisées, triées par plugin puis ordre de déclaration ; vide si rien n'est autorisé
	 */
	public List<Tuile> tuiles(ZoneNavigation zone, Long societeId) {
		if (zone == null) {
			throw new IllegalArgumentException("La zone de navigation est obligatoire.");
		}
		if (zone == ZoneNavigation.ADMIN) {
			return adminDuMaitre() ? tuilesAdmin() : List.of();
		}
		Contexte contexte = contexte(societeId, zone != ZoneNavigation.PUBLIQUE);
		if (contexte == null) {
			return List.of();
		}
		return collecter(Set.of(zone), contexte);
	}

	/** Entrées des plugins actifs dans ces zones : PUBLIQUE sans contrôle de droit, toutes les autres avec. */
	private List<Tuile> collecter(Set<ZoneNavigation> zones, Contexte contexte) {
		List<Tuile> resultat = new ArrayList<>();
		for (Map.Entry<String, PluginGeneration> actif : pluginsActifs().entrySet()) {
			ManifestePlugin m = manifeste(actif.getKey(), actif.getValue());
			if (m == null) {
				continue;
			}
			for (EntreeNavigation e : m.entrees()) {
				if (zones.contains(e.zone()) && (e.zone() == ZoneNavigation.PUBLIQUE || contexte.autorise(e.droit()))) {
					resultat.add(new Tuile(m.nom(), e.libelle(), e.icone(), e.chemin(), e.zone(),
							actif.getValue().version));
				}
			}
		}
		return List.copyOf(resultat);
	}

	/**
	 * @param societeId société ; {@code null} : société de la session
	 * @return les blocs de fiche autorisés, triés par plugin puis ordre de déclaration
	 */
	public List<BlocAutorise> blocs(Long societeId) {
		Contexte contexte = contexte(societeId, true);
		if (contexte == null) {
			return List.of();
		}
		List<BlocAutorise> resultat = new ArrayList<>();
		for (Map.Entry<String, PluginGeneration> actif : pluginsActifs().entrySet()) {
			ManifestePlugin m = manifeste(actif.getKey(), actif.getValue());
			if (m == null) {
				continue;
			}
			for (BlocFiche b : m.blocs()) {
				if (contexte.autorise(b.droit())) {
					resultat.add(new BlocAutorise(m.nom(), b.id(), b.libelle(), b.chemin(), actif.getValue().version));
				}
			}
		}
		return List.copyOf(resultat);
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

	/** Manifeste de la génération, ou {@code null} (entrée ignorée) s'il manque ou ne correspond pas au plugin. */
	private static ManifestePlugin manifeste(String nom, PluginGeneration gen) {
		try {
			ManifestePlugin m = gen == null ? null : gen.manifeste();
			if (m == null || !nom.equals(m.nom()) || m.entrees() == null || m.blocs() == null) {
				log.warn("Navigation : manifeste absent ou incohérent pour le plugin '{}', ignoré", nom);
				return null;
			}
			return m;
		}
		catch (RuntimeException e) {
			log.warn("Navigation : manifeste illisible pour le plugin '{}', ignoré", nom, e);
			return null;
		}
	}

	/** Contexte de la requête : utilisateur et société ; {@code null} si rien ne peut être autorisé. */
	private Contexte contexte(Long societeDemandee, boolean droitRequis) {
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
		if (societe == null && droitRequis) {
			return null;
		}
		return new Contexte(courante.userId(), societe);
	}

	/** Mémorise les décisions de droit le temps d'un appel (un même droit est souvent déclaré plusieurs fois). */
	private final class Contexte {

		private final Long userId;

		private final Long societeId;

		private final Map<String, Boolean> decisions = new HashMap<>();

		Contexte(Long userId, Long societeId) {
			this.userId = userId;
			this.societeId = societeId;
		}

		boolean autorise(String droit) {
			Boolean connu = decisions.get(droit);
			if (connu != null) {
				return connu;
			}
			boolean decision;
			try {
				decision = droits.autorise(userId, societeId, droit);
			}
			catch (RuntimeException e) {
				log.warn("Navigation : contrôle du droit '{}' en échec, refusé", droit, e);
				decision = false;
			}
			decisions.put(droit, decision);
			return decision;
		}

	}

}
