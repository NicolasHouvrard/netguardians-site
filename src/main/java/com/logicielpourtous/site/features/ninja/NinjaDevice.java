package com.logicielpourtous.site.features.ninja;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class NinjaDevice {

    @JsonProperty("id")
    private Integer id;

    @JsonProperty("displayName")
    private String displayName;

    @JsonProperty("systemName")
    private String systemName;

    @JsonProperty("nodeClass")
    private String nodeClass;

    @JsonProperty("online")
    private Boolean online;

    @JsonProperty("lastContact")
    private Long lastContact; // Unix timestamp (secondes)

    @JsonProperty("os")
    private Map<String, Object> os;

    // ── Getters ──────────────────────────────────────────────────────────

    public Integer getId()          { return id; }
    public String  getNodeClass()   { return nodeClass; }
    public Boolean getOnline()      { return Boolean.TRUE.equals(online); }

    public String getDisplayName() {
        return displayName != null ? displayName : systemName;
    }

    public String getOsName() {
        if (os == null) return "—";
        Object name = os.get("name");
        return name != null ? name.toString() : "—";
    }

    public LocalDateTime getLastContactTime() {
        if (lastContact == null || lastContact == 0) return null;
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(lastContact), ZoneId.systemDefault());
    }

    /** Icône FontAwesome selon le type d'appareil */
    public String getIcon() {
        if (nodeClass == null) return "fas fa-question-circle";
        return switch (nodeClass) {
            case "WINDOWS_WORKSTATION", "MAC"              -> "fas fa-desktop";
            case "WINDOWS_SERVER", "LINUX_SERVER"          -> "fas fa-server";
            case "LINUX_WORKSTATION"                       -> "fab fa-linux";
            case "ANDROID", "APPLE_IOS"                   -> "fas fa-mobile-alt";
            case "NMS_SWITCH", "NMS_ROUTER", "NMS_FIREWALL"-> "fas fa-network-wired";
            case "NMS_PRINTER"                             -> "fas fa-print";
            case "NMS_NAS"                                 -> "fas fa-database";
            default                                        -> "fas fa-desktop";
        };
    }

    public String getNodeClassLabel() {
        if (nodeClass == null) return "Inconnu";
        return switch (nodeClass) {
            case "WINDOWS_WORKSTATION" -> "Poste Windows";
            case "WINDOWS_SERVER"      -> "Serveur Windows";
            case "MAC"                 -> "Mac";
            case "LINUX_WORKSTATION"   -> "Poste Linux";
            case "LINUX_SERVER"        -> "Serveur Linux";
            case "ANDROID"             -> "Android";
            case "APPLE_IOS"           -> "iOS";
            case "NMS_SWITCH"          -> "Switch";
            case "NMS_ROUTER"          -> "Routeur";
            case "NMS_FIREWALL"        -> "Pare-feu";
            case "NMS_PRINTER"         -> "Imprimante";
            case "NMS_NAS"             -> "NAS";
            default -> nodeClass;
        };
    }
}
