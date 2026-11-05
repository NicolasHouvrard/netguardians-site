package com.logicielpourtous.site.features.accounting;

import com.logicielpourtous.site.features.commun.Tenant;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "qonto_transactions", schema = "public")
public class QontoTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(name = "qonto_id", unique = true, nullable = false)
    private String qontoId; // transaction_id from Qonto

    @Column(name = "operation_type")
    private String operationType; // "income", "transfer", "card", etc.

    private String side; // "credit" or "debit"

    @Column(precision = 15, scale = 2)
    private BigDecimal amount;

    private String currency;

    @Column(columnDefinition = "TEXT")
    private String label;

    private String reference;

    @Column(name = "settled_at")
    private LocalDate settledAt;

    @Column(name = "import_status", nullable = false)
    private String importStatus; // IMPORTED | SKIPPED | ERROR

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

    @Column(name = "attachment_ids", columnDefinition = "TEXT")
    private String attachmentIds; // comma-separated Qonto attachment IDs

    // Getters / Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getQontoId() { return qontoId; }
    public void setQontoId(String qontoId) { this.qontoId = qontoId; }
    public String getOperationType() { return operationType; }
    public void setOperationType(String operationType) { this.operationType = operationType; }
    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }
    public LocalDate getSettledAt() { return settledAt; }
    public void setSettledAt(LocalDate settledAt) { this.settledAt = settledAt; }
    public String getImportStatus() { return importStatus; }
    public void setImportStatus(String importStatus) { this.importStatus = importStatus; }
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
    public String getAttachmentIds() { return attachmentIds; }
    public void setAttachmentIds(String attachmentIds) { this.attachmentIds = attachmentIds; }
    public Tenant getTenant() { return tenant; }
    public void setTenant(Tenant tenant) { this.tenant = tenant; }
}
