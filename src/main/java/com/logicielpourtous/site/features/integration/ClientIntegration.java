package com.logicielpourtous.site.features.integration;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "client_integrations")
public class ClientIntegration {

    public enum ServiceType {
        NINJA_ONE      ("NinjaOne RMM",         "fas fa-shield-alt",  "#f59e0b"),
        MICROSOFT_365  ("Microsoft 365",         "fab fa-microsoft",   "#0078d4"),
        GOOGLE_WORKSPACE("Google Workspace",     "fab fa-google",      "#4285f4"),
        SLACK          ("Slack",                 "fab fa-slack",       "#4a154b"),
        CUSTOM         ("Personnalisé",           "fas fa-cog",         "#64748b");

        public final String label;
        public final String icon;
        public final String color;

        ServiceType(String label, String icon, String color) {
            this.label = label;
            this.icon  = icon;
            this.color = color;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ServiceType serviceType;

    private String label;
    private boolean enabled = true;

    // Champs génériques — utilisés selon le service
    private String apiKey;
    private String clientId;
    private String clientSecret;
    private String tenantId;     // Microsoft tenantId ou Google domain
    private String webhookUrl;   // Slack webhook, etc.
    private String notes;

    @Column(updatable = false)
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist  protected void onCreate() { createdAt = updatedAt = LocalDateTime.now(); }
    @PreUpdate   protected void onUpdate() { updatedAt = LocalDateTime.now(); }

    // Getters / Setters
    public Long          getId()          { return id; }
    public Long          getCustomerId()  { return customerId; }
    public ServiceType   getServiceType() { return serviceType; }
    public String        getLabel()       { return label; }
    public boolean       isEnabled()      { return enabled; }
    public String        getApiKey()      { return apiKey; }
    public String        getClientId()    { return clientId; }
    public String        getClientSecret(){ return clientSecret; }
    public String        getTenantId()    { return tenantId; }
    public String        getWebhookUrl()  { return webhookUrl; }
    public String        getNotes()       { return notes; }
    public LocalDateTime getCreatedAt()   { return createdAt; }
    public LocalDateTime getUpdatedAt()   { return updatedAt; }

    public void setCustomerId(Long v)     { customerId   = v; }
    public void setServiceType(ServiceType v) { serviceType = v; }
    public void setLabel(String v)        { label        = v; }
    public void setEnabled(boolean v)     { enabled      = v; }
    public void setApiKey(String v)       { apiKey       = v; }
    public void setClientId(String v)     { clientId     = v; }
    public void setClientSecret(String v) { clientSecret = v; }
    public void setTenantId(String v)     { tenantId     = v; }
    public void setWebhookUrl(String v)   { webhookUrl   = v; }
    public void setNotes(String v)        { notes        = v; }
}
