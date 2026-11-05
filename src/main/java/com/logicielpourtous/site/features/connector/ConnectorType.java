package com.logicielpourtous.site.features.connector;

public enum ConnectorType {
    WOOCOMMERCE("WooCommerce",     "fab fa-wordpress",    "La boutique WooCommerce de votre partenaire"),
    PRESTASHOP( "PrestaShop",      "fas fa-shopping-bag", "WebService PrestaShop"),
    SHOPIFY(    "Shopify",         "fas fa-store",        "API Admin Shopify"),
    INGRAM_MICRO("Ingram Micro",   "fas fa-warehouse",    "API catalogue Ingram Micro"),
    TECHDATA(   "Tech Data",       "fas fa-server",       "API catalogue Tech Data"),
    CUSTOM_REST("API personnalisée","fas fa-code",        "Endpoint REST générique retournant un tableau JSON");

    public final String label;
    public final String icon;
    public final String description;

    ConnectorType(String label, String icon, String description) {
        this.label       = label;
        this.icon        = icon;
        this.description = description;
    }
}
