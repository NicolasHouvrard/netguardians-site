package com.logicielpourtous.site.features.accounting;

public enum JournalType {
    OPENING("À-nouveau"),
    SALES("Ventes"),
    PURCHASES("Achats"),
    BANK("Banque"),
    CASH("Caisse"),
    MISC("Opérations diverses"),
    PAYROLL("Salaires");

    private final String label;
    JournalType(String label) { this.label = label; }
    public String getLabel() { return label; }
}
