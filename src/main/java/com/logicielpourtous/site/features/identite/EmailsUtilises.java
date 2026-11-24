package com.logicielpourtous.site.features.identite;

/**
 * Port sortant : sait si une adresse e-mail appartient deja a un utilisateur. Defini ici pour que le module
 * {@code identite} ne depende d'aucun autre module ; implemente par le module des utilisateurs.
 */
public interface EmailsUtilises {

    /** @param emailNormalise adresse en minuscules, espaces retires */
    boolean estUtilise(String emailNormalise);
}
