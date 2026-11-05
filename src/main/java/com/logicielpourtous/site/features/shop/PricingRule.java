package com.logicielpourtous.site.features.shop;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Entity
@Table(name = "shop_pricing_rules")
@Getter @Setter @NoArgsConstructor
public class PricingRule {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** Marge fixe ajoutée au PA avant application du pourcentage */
    @Column(name = "fixed_margin", precision = 10, scale = 2)
    private BigDecimal fixedMargin = BigDecimal.ZERO;

    /** Pourcentage appliqué sur (PA + marge fixe) */
    @Column(name = "percentage", precision = 10, scale = 4)
    private BigDecimal percentage = BigDecimal.ZERO;

    @Column(name = "is_default")
    private boolean isDefault = false;

    /**
     * Formule : (PA + fixedMargin) × (1 + percentage/100)
     * Chaque terme est optionnel (0 = pas d'effet).
     */
    public BigDecimal applyTo(BigDecimal purchasePrice) {
        if (purchasePrice == null) return null;
        BigDecimal margin = fixedMargin != null ? fixedMargin : BigDecimal.ZERO;
        BigDecimal pct    = percentage  != null ? percentage  : BigDecimal.ZERO;
        BigDecimal base   = purchasePrice.add(margin);
        return base
                .multiply(BigDecimal.ONE.add(pct.divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP)))
                .setScale(2, RoundingMode.HALF_UP);
    }

    public String getSummary() {
        BigDecimal m = fixedMargin != null ? fixedMargin : BigDecimal.ZERO;
        BigDecimal p = percentage  != null ? percentage  : BigDecimal.ZERO;
        boolean hasMarge = m.compareTo(BigDecimal.ZERO) != 0;
        boolean hasPct   = p.compareTo(BigDecimal.ZERO) != 0;

        BigDecimal coeff = BigDecimal.ONE.add(p.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP));

        if (hasMarge && hasPct) {
            return "(PA + " + m.stripTrailingZeros().toPlainString() + " €) × "
                    + coeff.stripTrailingZeros().toPlainString();
        } else if (hasMarge) {
            return "PA + " + m.stripTrailingZeros().toPlainString() + " €";
        } else if (hasPct) {
            return "PA × " + coeff.stripTrailingZeros().toPlainString()
                    + "  (" + p.stripTrailingZeros().toPlainString() + "%)";
        } else {
            return "PA (aucune marge)";
        }
    }
}
