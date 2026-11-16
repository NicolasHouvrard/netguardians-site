package com.logicielpourtous.site.features.plateforme;

/**
 * Entrée du catalogue du dépôt du Maître : une version publiée (jar + {@code .sig} vérifiés). {@code miseAJour} vaut
 * {@code chaud} ou {@code coeur} ; {@code coeurMax} peut être nul ; {@code sha256} (hexadécimal minuscule) et
 * {@code taille} portent sur les octets stockés.
 */
public record EntreeCatalogue(String nom, String version, String miseAJour, String coeurMin, String coeurMax,
		String sha256, long taille) {
}
