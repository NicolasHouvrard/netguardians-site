package com.logicielpourtous.site.features.shop;

import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.order.Order;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "shop_orders")
@Getter @Setter @NoArgsConstructor
public class ShopOrder {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Référence lisible : CMD-XXXXXXXX */
    @Column(unique = true, nullable = false, name = "reference")
    private String reference;

    // ── Coordonnées client ────────────────────────────────────────────────────
    private String email;

    @Column(name = "customer_name")
    private String customerName;

    private String phone;

    // ── Adresse de livraison ──────────────────────────────────────────────────
    @Column(name = "shipping_address")
    private String shippingAddress;

    @Column(name = "shipping_city")
    private String shippingCity;

    @Column(name = "shipping_zip")
    private String shippingZip;

    @Column(name = "shipping_country")
    private String shippingCountry = "France";

    // ── Statut & paiement ─────────────────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status = OrderStatus.PENDING_PAYMENT;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method")
    private PaymentMethod paymentMethod;

    @Column(name = "stripe_session_id", length = 500)
    private String stripeSessionId;

    @Column(name = "bank_ref")
    private String bankRef;

    // ── Montants ──────────────────────────────────────────────────────────────
    @Column(name = "total_ht", precision = 10, scale = 2)
    private BigDecimal totalHT = BigDecimal.ZERO;

    @Column(name = "vat_amount", precision = 10, scale = 2)
    private BigDecimal vatAmount = BigDecimal.ZERO;

    @Column(name = "total_ttc", precision = 10, scale = 2)
    private BigDecimal totalTTC = BigDecimal.ZERO;

    private String notes;

    // ── Lien client ───────────────────────────────────────────────────────────
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ShopOrderLine> lines = new ArrayList<>();

    /** Commande interne créée lorsque la commande boutique est validée */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "internal_order_id")
    private Order internalOrder;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.reference == null)
            this.reference = "CMD-" + UUID.randomUUID().toString().replace("-","").substring(0,8).toUpperCase();
    }

    // ── Enums ─────────────────────────────────────────────────────────────────

    public enum OrderStatus {
        PENDING_VALIDATION("Demande soumise", "info"),
        PENDING_PAYMENT("En attente de paiement", "warning"),
        AWAITING_TRANSFER("Attente virement", "primary"),
        PAID("Payée", "success"),
        PROCESSING("En cours de traitement", "primary"),
        SHIPPED("Expédiée", "primary"),
        DELIVERED("Livrée", "success"),
        CANCELLED("Annulée", "secondary"),
        REFUNDED("Remboursée", "secondary");

        public final String label;
        public final String badgeCss;
        OrderStatus(String label, String badgeCss) { this.label = label; this.badgeCss = badgeCss; }
    }

    public enum PaymentMethod {
        STRIPE("Carte bancaire (Stripe)"),
        BANK_TRANSFER("Virement bancaire");
        public final String label;
        PaymentMethod(String label) { this.label = label; }
    }
}
