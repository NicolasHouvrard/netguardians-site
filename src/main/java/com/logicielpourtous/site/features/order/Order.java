package com.logicielpourtous.site.features.order;

import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.invoice.Invoice;
import com.logicielpourtous.site.features.request.Request;
import com.logicielpourtous.site.features.team.Team;
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
@Table(name = "orders")
@Getter @Setter @NoArgsConstructor
public class Order {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_number")
    private String orderNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to_id")
    private User assignedTo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id")
    private Invoice invoice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id")
    private Request ticket;

    @Enumerated(EnumType.STRING)
    private OrderStatus status = OrderStatus.PENDING;

    @Column(name = "order_date")
    private LocalDate orderDate;

    @Column(name = "expected_date")
    private LocalDate expectedDate;

    @Column(name = "total_amount", precision = 10, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "total_tva", precision = 10, scale = 2)
    private BigDecimal totalTVA = BigDecimal.ZERO;

    @Column(name = "total_cost", precision = 10, scale = 2)
    private BigDecimal totalCost = BigDecimal.ZERO;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderLine> lines = new ArrayList<>();

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // ── Adresse de livraison structurée (pour passage fournisseur) ────────────

    @Column(name = "delivery_company", length = 200)
    private String deliveryCompany;

    @Column(name = "delivery_contact", length = 200)
    private String deliveryContact;

    @Column(name = "delivery_address1", length = 255)
    private String deliveryAddress1;

    @Column(name = "delivery_address2", length = 255)
    private String deliveryAddress2;

    @Column(name = "delivery_postal_code", length = 20)
    private String deliveryPostalCode;

    @Column(name = "delivery_city", length = 100)
    private String deliveryCity;

    @Column(name = "delivery_country", length = 2)
    private String deliveryCountry = "FR";

    @Column(name = "delivery_phone", length = 50)
    private String deliveryPhone;

    // ── Ingram Micro ──────────────────────────────────────────────────────────

    /** Numéro de commande retourné par Ingram Micro après passage automatique */
    @Column(name = "ingram_order_number", length = 100)
    private String ingramOrderNumber;

    /** Date/heure à laquelle la commande a été passée chez Ingram */
    @Column(name = "ingram_ordered_at")
    private LocalDateTime ingramOrderedAt;

    /** Message d'erreur si le passage chez Ingram a échoué */
    @Column(name = "ingram_order_error", columnDefinition = "TEXT")
    private String ingramOrderError;

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); }

    /** INTERNAL = créé manuellement, SHOP = généré depuis la boutique en ligne */
    public enum Source { INTERNAL, SHOP }

    /** CUSTOMER = livraison drop-shipping chez le client, RESELLER = livraison chez le revendeur (nous) */
    public enum DeliveryMode {
        CUSTOMER("Chez le client (drop-shipping)"),
        RESELLER("Chez le revendeur (livraison à nous)");
        public final String label;
        DeliveryMode(String label) { this.label = label; }
    }

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 20)
    private Source source = Source.INTERNAL;

    /** Référence de la ShopOrder d'origine (dénormalisé pour éviter une jointure) */
    @Column(name = "shop_order_ref")
    private String shopOrderRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_mode", length = 20, columnDefinition = "varchar(20) default 'CUSTOMER'")
    private DeliveryMode deliveryMode = DeliveryMode.CUSTOMER;

    public enum OrderStatus {
        PENDING("En attente"), CONFIRMED("Confirmée"), IN_PROGRESS("En cours"),
        DELIVERED("Livrée"), CANCELLED("Annulée");
        public final String label;
        public final String badgeCss;
        OrderStatus(String label) {
            this.label = label;
            this.badgeCss = switch(this.name()) {
                case "PENDING"     -> "text-bg-secondary";
                case "CONFIRMED"   -> "text-bg-primary";
                case "IN_PROGRESS" -> "text-bg-warning";
                case "DELIVERED"   -> "text-bg-success";
                case "CANCELLED"   -> "text-bg-danger";
                default            -> "text-bg-secondary";
            };
        }
    }
}
