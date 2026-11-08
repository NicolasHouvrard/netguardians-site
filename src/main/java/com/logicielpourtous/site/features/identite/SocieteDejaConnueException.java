package com.logicielpourtous.site.features.identite;

/**
 * La societe existe deja dans le registre. Le message est volontairement neutre et identique dans tous les
 * cas : il peut etre montre a un inconnu sans reveler a quel partenaire appartient la societe. La fiche
 * existante n'est accessible qu'au code appelant, jamais par le message.
 */
public class SocieteDejaConnueException extends RuntimeException {

    private final transient SocieteIdentite existante;

    /** {@code existante} peut etre {@code null} quand le conflit n'est detecte que par la base (acces concurrents). */
    public SocieteDejaConnueException(SocieteIdentite existante) {
        super("Cette société est déjà connue de la plateforme.");
        this.existante = existante;
    }

    public SocieteIdentite existante() {
        return existante;
    }
}
