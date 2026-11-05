package com.logicielpourtous.site.features.accounting;

import com.logicielpourtous.site.features.commun.Tenant;
import jakarta.persistence.*;

@Entity
@Table(name = "stripe_accounting_mappings", schema = "public",
       uniqueConstraints = @UniqueConstraint(columnNames = {"event_type", "tenant_id"}))
public class StripeAccountingMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(name = "event_type", nullable = false)
    private String eventType; // "payment_intent.succeeded", "payout.paid", "charge.refunded"

    @Column(nullable = false)
    private String label;

    @Column(name = "debit_account_number", nullable = false)
    private String debitAccountNumber; // e.g. "512100"

    @Column(name = "credit_account_number", nullable = false)
    private String creditAccountNumber; // e.g. "706000"

    private boolean active = true;

    // Getters / Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
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
