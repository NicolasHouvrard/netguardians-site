package com.logicielpourtous.site.features.plateforme;

import java.util.List;

/**
 * Résultat d'une installation ou mise à jour de plugin.
 *
 * @param statut issue de l'opération
 * @param plugin nom du plugin ({@code null} si le manifeste est illisible)
 * @param version version proposée par le jar ({@code null} si le manifeste est illisible)
 * @param raisons motifs d'un refus ou d'un échec (vide si {@link Statut#OK})
 * @param interruptionMs durée réelle du mode maintenance (0 quand il n'y a pas eu de maintenance)
 */
public record ResultatInstallation(Statut statut, String plugin, String version, List<String> raisons,
		long interruptionMs) {

	public enum Statut {
		/** Version installée et active. */
		OK,
		/** Refusée avant toute interruption : la version active (s'il y en a une) est intacte. */
		REFUSE,
		/** Échec sans retour possible : le plugin n'est plus actif (première installation ou retour arrière raté). */
		ECHEC,
		/** Échec de la nouvelle version, ancienne version relancée et servie. */
		ANNULE
	}

	public ResultatInstallation {
		raisons = List.copyOf(raisons);
	}

}
