package com.logicielpourtous.site.features.product;

import com.logicielpourtous.site.features.supplier.SupplierProduct;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "products")
@Getter @Setter @NoArgsConstructor
public class Product {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String sku;
    private String reference;

    @Column(precision = 10, scale = 2)
    private BigDecimal priceHT = BigDecimal.ZERO;

    @Column(name = "tax_rate", precision = 5, scale = 2)
    private BigDecimal taxRate = new BigDecimal("20.00");

    @Enumerated(EnumType.STRING)
    private ProductType type = ProductType.SERVICE;

    @Enumerated(EnumType.STRING)
    private PricingMode pricingMode = PricingMode.PER_UNIT;

    private String photoPath;

    private boolean active = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private ProductCategory category;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SupplierProduct> supplierProducts = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public enum ProductType {
        SERVICE("Service"), PHYSICAL("Produit physique"), LICENSE("Licence"), SUBSCRIPTION("Abonnement");
        public final String label;
        ProductType(String label) { this.label = label; }
    }

    public enum PricingMode {
        PER_UNIT("À l'unité"),
        PER_KG("Au kilo"),
        PER_HOUR("À l'heure"),
        PER_MONTH("Par mois"),
        PER_YEAR("Par an");
        public final String label;
        PricingMode(String label) { this.label = label; }
    }
}
