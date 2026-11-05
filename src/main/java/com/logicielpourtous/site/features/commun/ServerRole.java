package com.logicielpourtous.site.features.commun;

/**
 * Role que peut jouer une instance du serveur (fondation de la synchronisation multi-serveurs).
 */
public enum ServerRole {
    MASTER,
    PARTNER,
    CLIENT,
    PUBLIC
}
