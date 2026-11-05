package com.logicielpourtous.site.features.crm.quote;

public enum QuoteStatus {
    BROUILLON("Brouillon", "text-bg-secondary"),
    ENVOYE   ("Envoyé",    "text-bg-primary"),
    ACCEPTE  ("Accepté",   "text-bg-success"),
    REFUSE   ("Refusé",    "text-bg-danger"),
    EXPIRE   ("Expiré",    "text-bg-dark");

    public final String label;
    public final String badgeCss;

    QuoteStatus(String label, String badgeCss) {
        this.label    = label;
        this.badgeCss = badgeCss;
    }
}
