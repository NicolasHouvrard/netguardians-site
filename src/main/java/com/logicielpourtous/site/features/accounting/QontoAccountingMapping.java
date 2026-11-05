package com.logicielpourtous.site.features.accounting;

import com.logicielpourtous.site.features.commun.Tenant;
import jakarta.persistence.*;

@Entity
@Table(name = "qonto_accounting_mappings", schema = "public",
       uniqueConstraints = @UniqueConstraint(columnNames = {"mapping_key", "tenant_id"}))
public class QontoAccountingMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    // Key format: "{operationType}:{side}" e.g. "income:credit", "transfer:debit"
    @Column(name = "mapping_key", nullable = false)
    private String mappingKey;

    @Column(nullable = false)
    private String label; // human-readable name

    @Column(name = "debit_account_number", nullable = false)
    private String debitAccountNumber;

    @Column(name = "credit_account_number", nullable = false)
    private String creditAccountNumber;

    private boolean active = true;

    // Getters / Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getMappingKey() { return mappingKey; }
    public void setMappingKey(String mappingKey) { this.mappingKey = mappingKey; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getDebitAccountNumber() { return debitAccountNumber; }
    public void setDebitAccountNumber(String n) { this.debitAccountNumber = n; }
    public String getCreditAccountNumber() { return creditAccountNumber; }
    public void setCreditAccountNumber(String n) { this.creditAccountNumber = n; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Tenant getTenant() { return tenant; }
    public void setTenant(Tenant tenant) { this.tenant = tenant; }
}
