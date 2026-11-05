package com.logicielpourtous.site.features.stock;

import com.logicielpourtous.site.features.customers.Site;
import com.logicielpourtous.site.features.product.Product;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "stock_items",
       uniqueConstraints = @UniqueConstraint(columnNames = {"product_id", "site_id"}))
@Getter @Setter @NoArgsConstructor
public class StockItem {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "site_id", nullable = false)
    private Site site;

    /** Quantité actuelle en stock */
    private int quantity = 0;

    /** Seuil d'alerte stock bas (0 = pas d'alerte) */
    @Column(name = "min_quantity")
    private int minQuantity = 0;

    /** Emplacement physique dans le site (ex: "Étagère A3", "Réserve") */
    @Column(name = "location_label")
    private String locationLabel;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist @PreUpdate
    protected void onUpdate() { this.updatedAt = LocalDateTime.now(); }

    public boolean isLowStock() {
        return minQuantity > 0 && quantity <= minQuantity;
    }
}
