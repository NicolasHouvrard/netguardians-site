package com.logicielpourtous.site.features.request;




public enum RequestStatus {
    NEW("Nouveau"),
    IN_PROGRESS("En cours"),
    COMPLETED("Terminé"),
    CANCELLED("Annulé");

    private final String label;
    RequestStatus(String label) { this.label = label; }
    public String getLabel() { return label; }
}
