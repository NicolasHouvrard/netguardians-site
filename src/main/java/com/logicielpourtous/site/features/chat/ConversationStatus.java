package com.logicielpourtous.site.features.chat;

public enum ConversationStatus {
    PENDING("En attente",  "text-bg-warning"),
    OPEN   ("En cours",    "text-bg-success"),
    CLOSED ("Fermé",       "text-bg-secondary");

    public final String label;
    public final String badgeCss;

    ConversationStatus(String label, String badgeCss) {
        this.label    = label;
        this.badgeCss = badgeCss;
    }
}
