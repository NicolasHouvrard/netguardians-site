package com.logicielpourtous.site.features.shop;

import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.product.Product;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDateTime;

@Entity
@Table(name = "shop_products")
@Getter @Setter @NoArgsConstructor
public class ShopProduct {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    private boolean published = false;

    @Column(unique = true, nullable = false)
    private String slug;

    /** Titre affiché en boutique (remplace product.name si renseigné) */
    @Column(name = "shop_title")
    private String shopTitle;

    @Column(name = "shop_description", columnDefinition = "TEXT")
    private String shopDescription;

    // ── SEO ──────────────────────────────────────────────────────────────────
    @Column(name = "seo_title")
    private String seoTitle;

    @Column(name = "seo_description")
    private String seoDescription;

    @Column(name = "featured_image_path")
    private String featuredImagePath;

    @Column(name = "display_order")
    private int displayOrder = 0;

    // ── Prix ──────────────────────────────────────────────────────────────────
    /** Si null, utilise product.priceHT */
    @Column(name = "price_override", precision = 10, scale = 2)
    private BigDecimal priceOverride;

    /** Si null, utilise product.taxRate */
    @Column(name = "tax_rate_override", precision = 5, scale = 2)
    private BigDecimal taxRateOverride;

    // ── Stock boutique ────────────────────────────────────────────────────────
    @Column(name = "stock_enabled")
    private boolean stockEnabled = false;

    private int stock = 0;

    // ── Filtres catalogue ─────────────────────────────────────────────────────
    @Column(name = "brand")
    private String brand;

    @Column(name = "shop_category")
    private String shopCategory;

    // ── Vendeur (ESN partenaire) — null = produit de la plateforme ────────────
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id")
    private Customer vendor;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public String getDisplayTitle() {
        return shopTitle != null && !shopTitle.isBlank() ? shopTitle : product.getName();
    }

    public String getEffectiveDescription() {
        if (shopDescription != null && !shopDescription.isBlank()) return shopDescription;
        return product != null ? product.getDescription() : null;
    }

    public BigDecimal getEffectivePriceHT() {
        return priceOverride != null ? priceOverride : product.getPriceHT();
    }

    public BigDecimal getEffectiveTaxRate() {
        return taxRateOverride != null ? taxRateOverride : product.getTaxRate();
    }

    public BigDecimal getPriceTTC() {
        BigDecimal ht = getEffectivePriceHT();
        BigDecimal coeff = BigDecimal.ONE.add(getEffectiveTaxRate().divide(new BigDecimal("100")));
        return ht.multiply(coeff).setScale(2, RoundingMode.HALF_UP);
    }

    public boolean isAvailable() {
        return published && (!stockEnabled || stock > 0);
    }

    public static String slugify(String input) {
        if (input == null) return "";
        String n = Normalizer.normalize(input, Normalizer.Form.NFD);
        return n.replaceAll("[\\p{InCombiningDiacriticalMarks}]", "")
                .toLowerCase().trim()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }
}
