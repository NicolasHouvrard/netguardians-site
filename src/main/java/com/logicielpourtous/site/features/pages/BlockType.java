package com.logicielpourtous.site.features.pages;

public enum BlockType {
    RICHTEXT("Texte riche", "fas fa-paragraph"),
    IMAGE("Image", "fas fa-image"),
    HERO("Bannière hero", "fas fa-panorama"),
    CTA("Appel à l'action", "fas fa-bullhorn"),
    COLUMNS("Colonnes", "fas fa-columns"),
    TEXT_IMAGE("Texte + Image", "fas fa-table-columns"),
    IMAGE_TEXT("Image + Texte", "fas fa-image"),
    CARDS("Cartes", "fas fa-id-card"),
    WIDGET("Widget", "fas fa-puzzle-piece"),
    DIVIDER("Séparateur", "fas fa-minus"),
    SPACER("Espacement", "fas fa-arrows-alt-v"),
    HTML("HTML brut", "fas fa-code");

    private final String label;
    private final String icon;

    BlockType(String label, String icon) {
        this.label = label;
        this.icon = icon;
    }

    public String getLabel() { return label; }
    public String getIcon()  { return icon; }
}
