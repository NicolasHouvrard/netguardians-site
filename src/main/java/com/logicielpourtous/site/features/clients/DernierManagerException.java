package com.logicielpourtous.site.features.clients;

/** L'operation laisserait une societe sans manager : refusee (une societe a toujours au moins un manager). */
public class DernierManagerException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DernierManagerException(String message) {
        super(message);
    }
}
