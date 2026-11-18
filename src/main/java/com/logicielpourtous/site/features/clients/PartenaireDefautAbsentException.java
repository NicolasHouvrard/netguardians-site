package com.logicielpourtous.site.features.clients;

/**
 * Aucun partenaire par defaut utilisable : propriete vide, invalide, ou designant une societe sans capacite
 * PARTENAIRE. Jamais de client orphelin : l'operation est refusee.
 */
public class PartenaireDefautAbsentException extends RuntimeException {

    public PartenaireDefautAbsentException(String message) {
        super(message);
    }
}
