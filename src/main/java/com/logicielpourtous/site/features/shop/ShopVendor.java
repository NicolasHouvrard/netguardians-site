package com.logicielpourtous.site.features.shop;

import com.logicielpourtous.site.features.customers.Customer;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/** Vendeur partenaire (ESN) pouvant publier des produits via API */
@Entity
@Table(name = "shop_vendors")
@Getter @Setter @NoArgsConstructor
public class ShopVendor {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", unique = true)
    private Customer customer;

    @Column(name = "vendor_name")
    private String vendorName;

    @Column(name = "vendor_description", columnDefinition = "TEXT")
    private String vendorDescription;

    /** Clé API générée aléatoirement, partagée avec l'ESN */
    @Column(name = "api_key", unique = true, nullable = false)
    private String apiKey;

    private boolean active = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.apiKey == null) this.apiKey = UUID.randomUUID().toString();
    }

    public void regenerateApiKey() {
        this.apiKey = UUID.randomUUID().toString();
    }
}
