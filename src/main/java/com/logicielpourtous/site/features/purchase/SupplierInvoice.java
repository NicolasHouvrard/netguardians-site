package com.logicielpourtous.site.features.purchase;

import com.logicielpourtous.site.features.commun.Tenant;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "supplier_invoices", schema = "public")
public class SupplierInvoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(name = "vendor_name")
    private String vendorName;

    @Column(name = "invoice_number")
    private String invoiceNumber;

    @Column(name = "invoice_date")
    private LocalDate invoiceDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "total_ht", precision = 15, scale = 2)
    private BigDecimal totalHT;

    @Column(name = "total_tva", precision = 15, scale = 2)
    private BigDecimal totalTVA;

    @Column(name = "total_ttc", precision = 15, scale = 2)
    private BigDecimal totalTTC;

    @Column(length = 3)
    private String currency = "EUR";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.DRAFT;

    @Column(name = "charge_account_number", length = 10)
    private String chargeAccountNumber;

    @Column(name = "file_path")
    private String filePath;

    @Column(name = "original_filename")
    private String originalFilename;

    @Column(name = "qonto_transaction_id")
    private String qontoTransactionId;

    @Column(name = "accounting_entry_id")
    private Long accountingEntryId;

    @Column(name = "payment_entry_id")
    private Long paymentEntryId;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "ingram_invoice_number", length = 100)
    private String ingramInvoiceNumber;

    @Column(name = "ingram_order_number", length = 100)
    private String ingramOrderNumber;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum Status {
        DRAFT("À valider", "bg-secondary"),
        TO_PAY("À payer", "bg-warning text-dark"),
        PAID("Payée", "bg-success"),
        CANCELLED("Annulée", "bg-danger");

        private final String label;
        private final String badgeCss;

        Status(String label, String badgeCss) {
            this.label = label;
            this.badgeCss = badgeCss;
        }

        public String getLabel() { return label; }
        public String getBadgeCss() { return badgeCss; }
    }

    // Getters / Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Tenant getTenant() { return tenant; }
    public void setTenant(Tenant tenant) { this.tenant = tenant; }
    public String getVendorName() { return vendorName; }
    public void setVendorName(String vendorName) { this.vendorName = vendorName; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public void setInvoiceNumber(String invoiceNumber) { this.invoiceNumber = invoiceNumber; }
    public LocalDate getInvoiceDate() { return invoiceDate; }
    public void setInvoiceDate(LocalDate invoiceDate) { this.invoiceDate = invoiceDate; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public BigDecimal getTotalHT() { return totalHT; }
    public void setTotalHT(BigDecimal totalHT) { this.totalHT = totalHT; }
    public BigDecimal getTotalTVA() { return totalTVA; }
    public void setTotalTVA(BigDecimal totalTVA) { this.totalTVA = totalTVA; }
    public BigDecimal getTotalTTC() { return totalTTC; }
    public void setTotalTTC(BigDecimal totalTTC) { this.totalTTC = totalTTC; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getChargeAccountNumber() { return chargeAccountNumber; }
    public void setChargeAccountNumber(String chargeAccountNumber) { this.chargeAccountNumber = chargeAccountNumber; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
    public String getQontoTransactionId() { return qontoTransactionId; }
    public void setQontoTransactionId(String qontoTransactionId) { this.qontoTransactionId = qontoTransactionId; }
    public Long getAccountingEntryId() { return accountingEntryId; }
    public void setAccountingEntryId(Long accountingEntryId) { this.accountingEntryId = accountingEntryId; }
    public Long getPaymentEntryId() { return paymentEntryId; }
    public void setPaymentEntryId(Long paymentEntryId) { this.paymentEntryId = paymentEntryId; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getIngramInvoiceNumber() { return ingramInvoiceNumber; }
    public void setIngramInvoiceNumber(String ingramInvoiceNumber) { this.ingramInvoiceNumber = ingramInvoiceNumber; }
    public String getIngramOrderNumber() { return ingramOrderNumber; }
    public void setIngramOrderNumber(String ingramOrderNumber) { this.ingramOrderNumber = ingramOrderNumber; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
