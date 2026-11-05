package com.logicielpourtous.site.features.supplier;

public enum ApiType {
    NONE("Aucune"),
    INGRAM_MICRO("Ingram Micro"),
    TECHDATA("Tech Data"),
    CUSTOM("Personnalisé");

    public final String label;

    ApiType(String label) { this.label = label; }
}
