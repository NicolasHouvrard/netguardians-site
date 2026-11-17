package com.logicielpourtous.site.features.plateforme;

import java.io.IOException;

/**
 * Téléchargement refusé pour une cause qui se reproduirait à l'identique (corps plus gros que la taille du catalogue,
 * signature démesurée) : le tireur le traite comme un {@code REFUSE} mis de côté, pas comme une panne passagère.
 */
public class TelechargementRefuse extends IOException {

	public TelechargementRefuse(String message) {
		super(message);
	}

}
