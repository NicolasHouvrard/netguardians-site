package com.logicielpourtous.agent;

import java.util.List;

/**
 * Wrapper injectable autour de l'execution d'une commande systeme (Task 6). Permet aux tests de
 * remplacer {@link ProcessBuilderCommandeExecuteur} par un mock : la vraie CLI {@code docker} n'est
 * jamais invoquee en test.
 */
public interface CommandeExecuteur {

    /** Execute la commande (premier element = executable) et attend sa fin. */
    ResultatCommande executer(List<String> commande);
}
