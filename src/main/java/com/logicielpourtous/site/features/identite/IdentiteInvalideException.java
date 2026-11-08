package com.logicielpourtous.site.features.identite;

/** Numero d'identite de societe refuse ; {@link #champ()} designe le champ du formulaire en cause. */
public class IdentiteInvalideException extends IllegalArgumentException {

    private final String champ;

    public IdentiteInvalideException(String champ, String message) {
        super(message);
        this.champ = champ;
    }

    /** {@code "siren"}, {@code "siret"} ou {@code "tva"}. */
    public String champ() {
        return champ;
    }
}
