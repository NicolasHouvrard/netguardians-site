package com.logicielpourtous.site.sdk;

/** Levée quand un contrat (interface) fourni par un autre plugin n'est pas disponible. */
public class ContratIndisponibleException extends RuntimeException {

	public ContratIndisponibleException(String contrat) {
		super("Contrat indisponible : " + contrat);
	}
}
