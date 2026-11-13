package com.logicielpourtous.site.features.plateforme;

/**
 * Un plugin vu par l'administration : nom, version servie (ou en cours de remplacement) et état.
 *
 * @param nom nom du plugin
 * @param version version active, ou version remplacée pendant une mise à jour
 * @param etat {@link Etat#MAINTENANCE} pendant une mise à jour (503 sur ses routes), sinon {@link Etat#ACTIF}
 */
public record EtatPlugin(String nom, String version, Etat etat) {

	public enum Etat {
		ACTIF, MAINTENANCE
	}

}
