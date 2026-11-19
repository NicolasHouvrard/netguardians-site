package com.logicielpourtous.site.features.plateforme;

/**
 * Port de contrôle des droits d'un utilisateur sur une société cliente, implémenté par le module {@code clients}
 * ({@code DroitsService}). Il évite que {@code plateforme} dépende de {@code clients} (cycle de modules).
 */
public interface ControleDroits {

	/** Vrai seulement si le droit est accordé ; faux au moindre doute (entrée nulle, inconnue, absente). */
	boolean autorise(Long userId, Long societeId, String droit);

}
