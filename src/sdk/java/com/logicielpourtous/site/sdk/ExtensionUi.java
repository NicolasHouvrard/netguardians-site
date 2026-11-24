package com.logicielpourtous.site.sdk;

/**
 * Contribution d'un plugin à un emplacement d'écran d'un autre écran (bouton ou onglet), déclarée dans le manifeste
 * par {@code ext.N=emplacement|libellé|chemin|droit|ordre}.
 *
 * <p>SÉCURITÉ : le filtre par droit appliqué à l'affichage n'est PAS une protection. La route du plugin derrière
 * l'adresse doit elle-même contrôler les droits de l'utilisateur et l'appartenance de l'entité demandée.
 *
 * @param emplacement nom de l'emplacement, par exemple {@code clients.fiche.onglets}
 * @param libelle texte affiché (rendu en texte, jamais en HTML)
 * @param chemin chemin sous {@code /p/<plugin>}, commence par {@code /}, au plus un marqueur {@code {id}}
 * @param droit droit requis pour voir l'élément, au format {@link ManifestePlugin#DROIT_VALIDE}
 * @param ordre rang d'affichage, 0 à 9999
 */
public record ExtensionUi(String emplacement, String libelle, String chemin, String droit, int ordre) {
}
