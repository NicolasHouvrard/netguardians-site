package com.logicielpourtous.site.features.customers;

public enum ProspectSource {
    RECHERCHE_COMMERCIAL("Prospection commerciale"),
    INSCRIPTION_PORTAIL ("Inscription portail"),
    REFERRAL            ("Référence / Bouche à oreille"),
    LINKEDIN            ("LinkedIn"),
    SALON               ("Salon / Événement"),
    AUTRE               ("Autre");

    public final String label;

    ProspectSource(String label) { this.label = label; }
}
