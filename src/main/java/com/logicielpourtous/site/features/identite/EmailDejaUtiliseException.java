package com.logicielpourtous.site.features.identite;

/** L'e-mail d'un particulier est deja celui d'un utilisateur existant : refuse avant toute ecriture. */
public class EmailDejaUtiliseException extends RuntimeException {

    public EmailDejaUtiliseException() {
        super("Cette adresse e-mail est déjà utilisée.");
    }
}
