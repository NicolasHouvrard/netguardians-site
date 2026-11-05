package com.logicielpourtous.site.features.payment;

import com.logicielpourtous.site.features.commun.EncryptionConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "payment_configs")
@Getter @Setter @NoArgsConstructor
public class PaymentConfig {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true)
    private ConfigType type;

    // ── Virement bancaire ─────────────────────────────────────────────────────
    @Convert(converter = EncryptionConverter.class)
    @Column(columnDefinition = "TEXT")
    private String iban;

    @Convert(converter = EncryptionConverter.class)
    @Column(columnDefinition = "TEXT")
    private String bic;

    @Column(name = "beneficiary_name")
    private String beneficiaryName;

    // ── Stripe ────────────────────────────────────────────────────────────────
    @Convert(converter = EncryptionConverter.class)
    @Column(name = "stripe_secret_key", columnDefinition = "TEXT")
    private String stripeSecretKey;

    @Convert(converter = EncryptionConverter.class)
    @Column(name = "stripe_publishable_key", columnDefinition = "TEXT")
    private String stripePublishableKey;

    @Convert(converter = EncryptionConverter.class)
    @Column(name = "stripe_webhook_secret", columnDefinition = "TEXT")
    private String stripeWebhookSecret;

    private boolean active = false;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist @PreUpdate
    protected void onUpdate() { this.updatedAt = LocalDateTime.now(); }

    public boolean hasIban()             { return iban != null && !iban.isBlank(); }
    public boolean hasStripeSecretKey()  { return stripeSecretKey != null && !stripeSecretKey.isBlank(); }

    public enum ConfigType {
        BANK_TRANSFER("Virement bancaire"),
        STRIPE("Stripe");
        public final String label;
        ConfigType(String label) { this.label = label; }
    }
}
