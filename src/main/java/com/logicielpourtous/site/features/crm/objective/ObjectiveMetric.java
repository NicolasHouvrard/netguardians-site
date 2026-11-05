package com.logicielpourtous.site.features.crm.objective;

public enum ObjectiveMetric {
    CHIFFRE_AFFAIRES ("CA devis envoyés (€)"),
    NOUVEAUX_CLIENTS ("Nouveaux clients"),
    DEVIS_ENVOYES    ("Devis envoyés"),
    CONVERSIONS      ("Devis acceptés"),
    VALEUR_PIPELINE  ("Valeur pipeline prospects (€)");

    public final String label;
    ObjectiveMetric(String label) { this.label = label; }
}
