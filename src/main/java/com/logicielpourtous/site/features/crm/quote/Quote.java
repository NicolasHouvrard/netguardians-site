package com.logicielpourtous.site.features.crm.quote;

import com.logicielpourtous.site.features.customers.Customer;
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
@Table(name = "quotes")
@Getter @Setter @NoArgsConstructor
public class Quote {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "quote_number", unique = true)
    private String quoteNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "opportunity_id")
    private com.logicielpourtous.site.features.crm.opportunity.CrmOpportunity opportunity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to_id")
    private User assignedTo;

    @Enumerated(EnumType.STRING)
    private QuoteStatus status = QuoteStatus.BROUILLON;

    @Column(name = "quote_date")
    private LocalDate quoteDate;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "total_ht", precision = 10, scale = 2)
    private BigDecimal totalHT = BigDecimal.ZERO;

    @Column(name = "total_tva", precision = 10, scale = 2)
    private BigDecimal totalTVA = BigDecimal.ZERO;

    @Column(name = "total_ttc", precision = 10, scale = 2)
    private BigDecimal totalTTC = BigDecimal.ZERO;

    private int version = 1;

    @Column(name = "converted_order_id")
    private Long convertedOrderId;

    @Column(name = "converted_invoice_id")
    private Long convertedInvoiceId;

    @OneToMany(mappedBy = "quote", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<QuoteLine> lines = new ArrayList<>();

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.quoteDate == null)  this.quoteDate  = LocalDate.now();
        if (this.validUntil == null) this.validUntil = LocalDate.now().plusDays(30);
    }
}
