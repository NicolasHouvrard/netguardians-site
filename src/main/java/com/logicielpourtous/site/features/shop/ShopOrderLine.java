package com.logicielpourtous.site.features.shop;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "shop_order_lines")
@Getter @Setter @NoArgsConstructor
public class ShopOrderLine {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private ShopOrder order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shop_product_id")
    private ShopProduct shopProduct;

    /** Dénormalisé — conservé même si le produit est supprimé */
    @Column(name = "product_name", nullable = false)
    private String productName;

    private int quantity;

    @Column(name = "unit_price_ht", precision = 10, scale = 2)
    private BigDecimal unitPriceHT;

    @Column(name = "tax_rate", precision = 5, scale = 2)
    private BigDecimal taxRate;

    @Column(name = "line_total_ht", precision = 10, scale = 2)
    private BigDecimal lineTotalHT;

    @Column(name = "line_total_ttc", precision = 10, scale = 2)
    private BigDecimal lineTotalTTC;
}
