package com.logicielpourtous.site.features.plateforme;

/**
 * Port des capacités d'une société (PARTENAIRE, CLIENT...), implémenté par le module {@code clients}
 * ({@code societe_capacites}). Il décide de la zone de navigation d'une société sans que {@code plateforme} dépende
 * de {@code clients} (cycle de modules).
 */
public interface ControleCapacites {

	/** Vrai seulement si la société a cette capacité ; faux au moindre doute (société nulle, inconnue, capacité inconnue). */
	boolean aCapacite(Long societeId, String capacite);

}
