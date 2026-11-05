package com.logicielpourtous.agent;

/** Resultat d'une commande systeme : code de sortie, sortie standard et sortie d'erreur. */
public record ResultatCommande(int codeSortie, String sortieStandard, String sortieErreur) {
}
