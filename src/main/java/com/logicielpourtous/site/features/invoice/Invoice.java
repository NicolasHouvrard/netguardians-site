package com.logicielpourtous.site.features.invoice;

import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.request.Request;
import com.logicielpourtous.site.features.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "invoices")
@Getter @Setter @NoArgsConstructor
public class Invoice {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_number", unique = true)
    private String invoiceNumber;

    @Enumerated(EnumType.STRING)
    private InvoiceType type = InvoiceType.SALE;

    @Enumerated(EnumType.STRING)
    private InvoiceStatus status = InvoiceStatus.DRAFT;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id")
    private Request ticket;

    @Column(name = "invoice_date")
    private LocalDate invoiceDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(precision = 10, scale = 2)
    private BigDecimal totalHT = BigDecimal.ZERO;

    @Column(name = "tax_rate", precision = 5, scale = 2)
    private BigDecimal taxRate = new BigDecimal("20.00");

    @Column(name = "total_tva", precision = 10, scale = 2)
    private BigDecimal totalTVA = BigDecimal.ZERO;

    @Column(name = "total_ttc", precision = 10, scale = 2)
    private BigDecimal totalTTC = BigDecimal.ZERO;

    @Column(name = "total_cost", precision = 10, scale = 2)
    private BigDecimal totalCost = BigDecimal.ZERO;

    @Column(name = "vat_mention", columnDefinition = "TEXT")
    private String vatMention;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<InvoiceLine> lines = new ArrayList<>();

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // ── Facturation électronique (Qonto PDP) ─────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(name = "e_invoicing_status", columnDefinition = "varchar(50) default 'NONE'")
    private EInvoicingStatus eInvoicingStatus = EInvoicingStatus.NONE;

    @Column(name = "e_invoicing_id")
    private String eInvoicingId;

    @Column(name = "e_invoicing_submitted_at")
    private LocalDateTime eInvoicingSubmittedAt;

    @Column(name = "e_invoicing_message", columnDefinition = "TEXT")
    private String eInvoicingMessage;

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); }

    public enum EInvoicingStatus {
        NONE("Non soumise"), SUBMITTED("Soumise"), ACCEPTED("Acceptée"), REJECTED("Rejetée"), ERROR("Erreur");
        public final String label;
        EInvoicingStatus(String label) { this.label = label; }
        public String badgeCss() {
            return switch (this) {
                case NONE      -> "text-bg-light border";
                case SUBMITTED -> "text-bg-primary";
                case ACCEPTED  -> "text-bg-success";
                case REJECTED  -> "text-bg-danger";
                case ERROR     -> "text-bg-warning";
            };
        }
    }

    public enum InvoiceType {
        SALE("Vente"), PURCHASE("Achat");
        public final String label;
        InvoiceType(String label) { this.label = label; }
    }

    public enum InvoiceStatus {
        DRAFT("Brouillon"), SENT("Envoyée"), PAID("Payée"), OVERDUE("En retard"), CANCELLED("Annulée");
        public final String label;
        public final String badgeCss;
        InvoiceStatus(String label) {
            this.label = label;
            this.badgeCss = switch(this.name()) {
                case "DRAFT"     -> "text-bg-secondary";
                case "SENT"      -> "text-bg-primary";
                case "PAID"      -> "text-bg-success";
                case "OVERDUE"   -> "text-bg-danger";
                case "CANCELLED" -> "text-bg-dark";
                default          -> "text-bg-secondary";
            };
        }
    }
}
