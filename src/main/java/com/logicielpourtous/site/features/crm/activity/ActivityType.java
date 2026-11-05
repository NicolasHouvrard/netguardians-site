package com.logicielpourtous.site.features.crm.activity;

public enum ActivityType {
    APPEL       ("Appel téléphonique",  "fas fa-phone"),
    EMAIL       ("E-mail",              "fas fa-envelope"),
    RENDEZ_VOUS ("Rendez-vous",         "fas fa-calendar-check"),
    DEMO        ("Démonstration",       "fas fa-laptop"),
    PROPOSITION ("Envoi proposition",   "fas fa-file-signature"),
    RELANCE     ("Relance",             "fas fa-rotate-right"),
    AUTRE       ("Autre",               "fas fa-circle-dot");

    public final String label;
    public final String icon;

    ActivityType(String label, String icon) {
        this.label = label;
        this.icon  = icon;
    }
}
