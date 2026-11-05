package com.logicielpourtous.site.features.supplier;

import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.product.Product;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "supplier_products")
@Getter @Setter @NoArgsConstructor
public class SupplierProduct {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Customer supplier;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** Référence fournisseur (ex : Ingram part number) */
    private String supplierReference;

    /** Nom du produit chez le fournisseur */
    private String supplierName;

    /** Référence fabricant (vendor part number) */
    @Column(name = "vendor_part_number")
    private String vendorPartNumber;

    /** Marque / éditeur */
    @Column(name = "vendor_name")
    private String vendorName;

    /** Code UPC/EAN */
    @Column(name = "upc_code")
    private String upcCode;

    /** Sous-catégorie fournisseur */
    @Column(name = "sub_category")
    private String subCategory;

    @Column(precision = 10, scale = 2)
    private BigDecimal purchasePrice;

    private Integer stockQuantity;

    private boolean available = true;

    private LocalDateTime lastSyncAt;
}
