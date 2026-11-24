package com.logicielpourtous.site.sdk;

import java.util.Set;

/**
 * Contrat d'identité offert aux plugins par le noyau : qui est l'utilisateur de la requête en cours. Seuls des types du
 * JDK sont échangés (jamais un type partagé avec le noyau).
 */
public interface IdentiteContrat {

	/**
	 * Identité de l'utilisateur authentifié de la requête en cours.
	 * @throws IllegalStateException hors de tout contexte authentifié (ou tant qu'une société reste à choisir)
	 */
	IdentiteCourante courante();

	/**
	 * @param userId identifiant de l'utilisateur
	 * @param roles rôles (autorités) de l'utilisateur pour la société active
	 * @param societeId société de l'appartenance choisie pour la session ; {@code null} si cette appartenance n'est pas
	 * rattachée à une société du registre (appartenance historique) ou s'il n'y en a pas
	 * @param tenantId tenant courant ; {@code null} s'il n'y en a pas
	 * @param roleServeur rôle du serveur (MASTER, PARTNER...)
	 */
	record IdentiteCourante(Long userId, Set<String> roles, Long societeId, Long tenantId, String roleServeur) {

		public IdentiteCourante {
			roles = roles == null ? Set.of() : Set.copyOf(roles);
		}

	}

}
