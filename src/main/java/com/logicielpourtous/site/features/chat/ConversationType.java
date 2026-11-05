package com.logicielpourtous.site.features.chat;

public enum ConversationType {
    PUBLIC("Public"),
    INTERNAL("Interne"),
    CLIENT("Client");

    public final String label;
    ConversationType(String label) { this.label = label; }
}
