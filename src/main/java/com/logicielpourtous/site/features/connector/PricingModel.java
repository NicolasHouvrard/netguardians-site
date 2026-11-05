package com.logicielpourtous.site.features.connector;

public enum PricingModel {
    ADD_MARGIN(  "Marge ajoutée",  "Notre prix = prix achat × (1 + marge %)"),
    COMMISSION(  "Commission",     "L'ESN garde (100 - commission %) des ventes"),
    MANUAL(      "Manuel",         "Les prix ne sont pas mis à jour automatiquement");

    public final String label;
    public final String description;

    PricingModel(String label, String description) {
        this.label       = label;
        this.description = description;
    }
}
