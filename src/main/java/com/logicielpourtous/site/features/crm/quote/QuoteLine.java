package com.logicielpourtous.site.features.crm.quote;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "quote_lines")
@Getter @Setter @NoArgsConstructor
public class QuoteLine {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quote_id", nullable = false)
    private Quote quote;

    private String description;
    private Integer quantity = 1;

    @Column(name = "unit_price", precision = 10, scale = 2)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    @Column(name = "total_ht", precision = 10, scale = 2)
    private BigDecimal totalHT = BigDecimal.ZERO;

    @Column(name = "vat_rate", precision = 5, scale = 2)
    private BigDecimal vatRate = new BigDecimal("20.00");

    @Column(name = "vat_rate_label", length = 100)
    private String vatRateLabel;

    @Column(name = "total_tva", precision = 10, scale = 2)
    private BigDecimal totalTVA = BigDecimal.ZERO;

    @Column(name = "cost_price", precision = 10, scale = 2)
    private BigDecimal costPrice;

    @Column(name = "total_cost", precision = 10, scale = 2)
    private BigDecimal totalCost;

    private Integer position;
}
