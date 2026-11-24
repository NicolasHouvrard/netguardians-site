package com.logicielpourtous.site.features.clients;

/** Entree refusee dans l'espace d'un client ; le message est volontairement identique quelle qu'en soit la cause. */
public class AccesRefuseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AccesRefuseException() {
        super("Acces refuse.");
    }
}
