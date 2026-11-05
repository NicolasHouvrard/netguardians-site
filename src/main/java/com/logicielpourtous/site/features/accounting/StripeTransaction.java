package com.logicielpourtous.site.features.accounting;

import com.logicielpourtous.site.features.commun.Tenant;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "stripe_transactions", schema = "public")
public class StripeTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(name = "stripe_id", unique = true, nullable = false)
    private String stripeId;

    @Column(name = "stripe_event_type")
    private String stripeEventType;

    @Column(precision = 15, scale = 2)
    private BigDecimal amount;

    private String currency;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "event_date")
    private LocalDate eventDate;

    @Column(name = "stripe_status", nullable = false)
    private String stripeStatus; // IMPORTED | SKIPPED | ERROR

    @Column(name = "accounting_entry_id")
    private Long accountingEntryId;

    @Column(name = "imported_at")
    private LocalDateTime importedAt = LocalDateTime.now();

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean reconciled = false;

    @Column(name = "bank_account_number")
    private String bankAccountNumber;

    // Getters / Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getStripeId() { return stripeId; }
    public void setStripeId(String stripeId) { this.stripeId = stripeId; }
    public String getStripeEventType() { return stripeEventType; }
    public void setStripeEventType(String t) { this.stripeEventType = t; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDate getEventDate() { return eventDate; }
    public void setEventDate(LocalDate eventDate) { this.eventDate = eventDate; }
    public String getStripeStatus() { return stripeStatus; }
    public void setStripeStatus(String stripeStatus) { this.stripeStatus = stripeStatus; }
    public Long getAccountingEntryId() { return accountingEntryId; }
    public void setAccountingEntryId(Long accountingEntryId) { this.accountingEntryId = accountingEntryId; }
    public LocalDateTime getImportedAt() { return importedAt; }
    public void setImportedAt(LocalDateTime importedAt) { this.importedAt = importedAt; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public boolean isReconciled() { return reconciled; }
    public void setReconciled(boolean reconciled) { this.reconciled = reconciled; }
    public String getBankAccountNumber() { return bankAccountNumber; }
    public void setBankAccountNumber(String bankAccountNumber) { this.bankAccountNumber = bankAccountNumber; }
    public Tenant getTenant() { return tenant; }
    public void setTenant(Tenant tenant) { this.tenant = tenant; }
}
