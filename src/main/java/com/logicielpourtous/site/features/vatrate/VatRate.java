package com.logicielpourtous.site.features.vatrate;

import com.logicielpourtous.site.features.customers.Customer;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "vat_rates")
@Getter @Setter @NoArgsConstructor
public class VatRate {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Column(precision = 5, scale = 2)
    private BigDecimal rate;

    @Enumerated(EnumType.STRING)
    private Customer.FiscalZone zone = Customer.FiscalZone.FRANCE;

    @Column(name = "is_default")
    private boolean isDefault = false;

    private boolean active = true;

    public String getLabel() {
        return rate.stripTrailingZeros().toPlainString() + "% — " + name;
    }
}
