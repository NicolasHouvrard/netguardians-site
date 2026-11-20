package com.logicielpourtous.site.sdk;

/**
 * Bloc affiché dans la fiche d'un client, déclaré par un plugin.
 *
 * @param id identifiant du bloc, au format {@link ManifestePlugin#NOM_VALIDE}
 * @param libelle titre affiché
 * @param chemin chemin de l'écran du bloc, commence par {@code /}
 * @param droit droit requis pour voir le bloc, au format {@link ManifestePlugin#DROIT_VALIDE}
 */
public record BlocFiche(String id, String libelle, String chemin, String droit) {
}
