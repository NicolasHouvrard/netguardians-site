package com.logicielpourtous.site.features.identite;

/**
 * Conflit d'unicite detecte par la BASE au moment d'enregistrer une societe (deux inscriptions simultanees
 * de la meme societe). Contrairement a {@link SocieteDejaConnueException}, la transaction en cours est alors
 * deja avortee par la base : elle ne peut pas etre poursuivie, l'appelant doit la recommencer.
 */
public class EnregistrementConcurrentException extends RuntimeException {

    public EnregistrementConcurrentException(Throwable cause) {
        super("Cette société est déjà connue de la plateforme.", cause);
    }
}
