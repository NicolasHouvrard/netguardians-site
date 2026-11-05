package com.logicielpourtous.site.features.shop;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "shop_price_history")
@Getter @Setter @NoArgsConstructor
public class ShopPriceHistory {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "old_price", precision = 10, scale = 2)
    private BigDecimal oldPrice;

    @Column(name = "new_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal newPrice;

    /** "Règle: PA+10€" ou "Manuel" */
    @Column(nullable = false)
    private String source;

    @Column(name = "changed_by")
    private String changedBy;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt = LocalDateTime.now();

    public static ShopPriceHistory of(Long productId, BigDecimal oldPrice,
                                      BigDecimal newPrice, String source, String changedBy) {
        ShopPriceHistory h = new ShopPriceHistory();
        h.productId  = productId;
        h.oldPrice   = oldPrice;
        h.newPrice   = newPrice;
        h.source     = source;
        h.changedBy  = changedBy;
        return h;
    }
}
