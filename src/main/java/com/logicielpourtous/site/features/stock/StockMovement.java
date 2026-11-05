package com.logicielpourtous.site.features.stock;

import com.logicielpourtous.site.features.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "stock_movements")
@Getter @Setter @NoArgsConstructor
public class StockMovement {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_item_id", nullable = false)
    private StockItem stockItem;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MovementType type;

    /** Quantité déplacée (toujours positive — le sens est donné par type) */
    @Column(nullable = false)
    private int quantity;

    /** Quantité après mouvement (snapshot) */
    @Column(name = "quantity_after")
    private int quantityAfter;

    /** Raison / commentaire libre */
    private String reason;

    /** Référence externe (numéro de commande, intervention…) */
    private String reference;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private User createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); }

    public enum MovementType {
        ENTRY("Entrée", "success", "fa-arrow-down"),
        EXIT("Sortie", "danger", "fa-arrow-up"),
        ADJUSTMENT("Ajustement", "secondary", "fa-sliders"),
        TRANSFER_OUT("Transfert sortant", "warning", "fa-truck"),
        TRANSFER_IN("Transfert entrant", "info", "fa-truck");

        public final String label;
        public final String badgeCss;
        public final String icon;

        MovementType(String label, String badgeCss, String icon) {
            this.label = label;
            this.badgeCss = badgeCss;
            this.icon = icon;
        }
    }
}
