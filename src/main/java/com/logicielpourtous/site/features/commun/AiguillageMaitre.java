package com.logicielpourtous.site.features.commun;

import java.util.List;

/**
 * Appel du Maitre pour l'aiguillage de la connexion unique (Authelia) : « ou habite cette identite ? ». Interface de
 * {@code commun} implementee par le module {@code licence} (client du Maitre), pour que {@code auth} ne depende pas
 * de {@code licence}. Absente sur le Maitre lui-meme.
 * <p>
 * Le jeton d'identite est un secret : ne jamais le journaliser ni le placer dans un message d'erreur.
 */
public interface AiguillageMaitre {

	/** Une societe de l'utilisateur et l'adresse publique du serveur qui l'heberge (telles que renvoyees par le Maitre). */
	record Destination(Long societeId, String nomSociete, String urlPublique) {
	}

	/**
	 * Liste des destinations du Maitre pour ce jeton d'identite ; vide si rien. Leve une exception si le Maitre est
	 * injoignable ou repond en erreur.
	 */
	List<Destination> aiguiller(String idToken);

}
