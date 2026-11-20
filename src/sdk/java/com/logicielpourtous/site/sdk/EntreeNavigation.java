package com.logicielpourtous.site.sdk;

/**
 * Tuile de navigation déclarée par un plugin.
 *
 * @param zone zone où la tuile apparaît
 * @param libelle texte affiché
 * @param icone nom de l'icône
 * @param chemin chemin de l'écran, commence par {@code /}
 * @param droit droit requis pour voir la tuile, au format {@link ManifestePlugin#DROIT_VALIDE}
 */
public record EntreeNavigation(ZoneNavigation zone, String libelle, String icone, String chemin, String droit) {
}
