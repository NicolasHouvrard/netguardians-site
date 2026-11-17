package com.logicielpourtous.site.features.commun;

import java.util.Optional;

/**
 * Clé API de licence de cette installation (en-tête {@code X-Licence-Api-Key}), pour un module qui appelle le Maître
 * sans dépendre du module {@code licence} (ex. le tireur de plugins de {@code plateforme}, qui ne dépend que de
 * {@code commun}). Implémentée par le module {@code licence}, qui la lit en base à chaque appel (elle n'existe qu'une
 * fois l'installation enrôlée).
 * <p>
 * La valeur est un secret : ne jamais la journaliser ni la placer dans un message d'erreur.
 */
public interface CleMaitre {

	/** Clé API courante, vide tant que l'installation n'est pas enrôlée auprès du Maître. */
	Optional<String> cle();

}
