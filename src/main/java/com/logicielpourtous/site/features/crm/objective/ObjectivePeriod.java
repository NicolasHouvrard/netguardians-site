package com.logicielpourtous.site.features.crm.objective;

public enum ObjectivePeriod {
    MENSUEL     ("Mensuel"),
    TRIMESTRIEL ("Trimestriel"),
    ANNUEL      ("Annuel");

    public final String label;
    ObjectivePeriod(String label) { this.label = label; }
}
