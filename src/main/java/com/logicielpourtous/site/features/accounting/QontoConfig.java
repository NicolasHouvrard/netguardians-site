package com.logicielpourtous.site.features.accounting;

import com.logicielpourtous.site.features.commun.EncryptionConverter;
import com.logicielpourtous.site.features.commun.Tenant;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "qonto_config", schema = "public")
public class QontoConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(name = "label")
    private String label = "Compte principal";

    @Column(name = "organization_slug")
    private String organizationSlug;

    @Convert(converter = EncryptionConverter.class)
    @Column(name = "secret_key", columnDefinition = "TEXT")
    private String secretKey;

    @Column(name = "iban")
    private String iban;

    @Column(name = "bank_account_label")
    private String bankAccountLabel;

    /** Numéro de compte PCG assigné à ce compte bancaire (ex: 512100) */
    @Column(name = "bank_account_number")
    private String bankAccountNumber;

    private Boolean active = Boolean.TRUE;

    // ── Synchronisation automatique ───────────────────────────────────────────

    @Column(name = "sync_enabled")
    private Boolean syncEnabled = Boolean.FALSE;

    @Column(name = "sync_start_hour")
    private Integer syncStartHour = 8;

    @Column(name = "sync_end_hour")
    private Integer syncEndHour = 18;

    @Column(name = "sync_interval_minutes")
    private Integer syncIntervalMinutes = 60;

    @Column(name = "last_sync_at")
    private LocalDateTime lastSyncAt;

    // Getters / Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Tenant getTenant() { return tenant; }
    public void setTenant(Tenant tenant) { this.tenant = tenant; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getOrganizationSlug() { return organizationSlug; }
    public void setOrganizationSlug(String organizationSlug) { this.organizationSlug = organizationSlug; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public String getIban() { return iban; }
    public void setIban(String iban) { this.iban = iban; }
    public String getBankAccountLabel() { return bankAccountLabel; }
    public void setBankAccountLabel(String bankAccountLabel) { this.bankAccountLabel = bankAccountLabel; }
    public String getBankAccountNumber() { return bankAccountNumber; }
    public void setBankAccountNumber(String bankAccountNumber) { this.bankAccountNumber = bankAccountNumber; }
    public boolean isActive() { return Boolean.TRUE.equals(active); }
    public void setActive(boolean active) { this.active = active; }
    public boolean isSyncEnabled() { return Boolean.TRUE.equals(syncEnabled); }
    public void setSyncEnabled(boolean syncEnabled) { this.syncEnabled = syncEnabled; }
    public int getSyncStartHour() { return syncStartHour != null ? syncStartHour : 8; }
    public void setSyncStartHour(int syncStartHour) { this.syncStartHour = syncStartHour; }
    public int getSyncEndHour() { return syncEndHour != null ? syncEndHour : 18; }
    public void setSyncEndHour(int syncEndHour) { this.syncEndHour = syncEndHour; }
    public int getSyncIntervalMinutes() { return syncIntervalMinutes != null ? syncIntervalMinutes : 60; }
    public void setSyncIntervalMinutes(int syncIntervalMinutes) { this.syncIntervalMinutes = syncIntervalMinutes; }
    public LocalDateTime getLastSyncAt() { return lastSyncAt; }
    public void setLastSyncAt(LocalDateTime lastSyncAt) { this.lastSyncAt = lastSyncAt; }
    public boolean isConfigured() { return organizationSlug != null && !organizationSlug.isBlank() && secretKey != null && !secretKey.isBlank(); }
}
