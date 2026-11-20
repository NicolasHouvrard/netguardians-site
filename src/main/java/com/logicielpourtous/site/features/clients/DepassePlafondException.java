package com.logicielpourtous.site.features.clients;

/** Le manager a voulu accorder a un role un droit que le partenaire n'autorise pas (hors plafond). */
public class DepassePlafondException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DepassePlafondException(String message) {
        super(message);
    }
}
