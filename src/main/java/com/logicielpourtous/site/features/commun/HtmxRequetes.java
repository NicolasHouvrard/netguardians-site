package com.logicielpourtous.site.features.commun;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Règle unique « fragment seulement » des écrans du socle : une réponse partielle n'est renvoyée que pour une requête
 * HTMX ordinaire (recherche, tri, onglet). Une navigation « boostée » ({@code HX-Boosted}) ou une restauration de
 * l'historique ({@code HX-History-Restore-Request}) envoie aussi {@code HX-Request: true}, mais remplace tout le corps
 * de la page : elle doit recevoir la page complète.
 */
public final class HtmxRequetes {

	private HtmxRequetes() {
	}

	/** Vrai si la réponse doit être un fragment : {@code HX-Request} présent, ni boostée ni restauration d'historique. */
	public static boolean fragmentSeulement(HttpServletRequest requete) {
		return vrai(requete.getHeader("HX-Request")) && !vrai(requete.getHeader("HX-Boosted"))
				&& !vrai(requete.getHeader("HX-History-Restore-Request"));
	}

	private static boolean vrai(String valeur) {
		return "true".equalsIgnoreCase(valeur);
	}

}
