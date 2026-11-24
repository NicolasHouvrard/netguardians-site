package com.logicielpourtous.site.sdk;

/**
 * Tuile de navigation déclarée par un plugin.
 *
 * @param zone zone où la tuile apparaît
 * @param libelle texte affiché
 * @param icone nom de l'icône (Bootstrap Icons, sans préfixe)
 * @param chemin chemin de l'écran, commence par {@code /}
 * @param droit droit requis pour voir la tuile, au format {@link ManifestePlugin#DROIT_VALIDE}
 * @param couleur couleur de la tuile, dans {@link ManifestePlugin#COULEURS_TUILE}
 */
public record EntreeNavigation(ZoneNavigation zone, String libelle, String icone, String chemin, String droit,
		String couleur) {

	/** Couleur par défaut d'une tuile. */
	public static final String COULEUR_PAR_DEFAUT = "primaire";

	/** Entrée sans couleur déclarée : couleur {@value #COULEUR_PAR_DEFAUT}. */
	public EntreeNavigation(ZoneNavigation zone, String libelle, String icone, String chemin, String droit) {
		this(zone, libelle, icone, chemin, droit, COULEUR_PAR_DEFAUT);
	}
}
