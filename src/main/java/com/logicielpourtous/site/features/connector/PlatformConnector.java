package com.logicielpourtous.site.features.connector;

import com.logicielpourtous.site.features.commun.EncryptionConverter;
import com.logicielpourtous.site.features.customers.Customer;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "platform_connectors")
@Getter @Setter @NoArgsConstructor
public class PlatformConnector {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nom d'affichage (ex: "Catalog WooCommerce ESN Tech Paris") */
    @Column(nullable = false)
    private String name;

    /** Client ESN partenaire (optionnel) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConnectorType connectorType = ConnectorType.CUSTOM_REST;

    /** URL de base de l'API externe (chiffrée) */
    @Convert(converter = EncryptionConverter.class)
    @Column(name = "api_url", length = 512)
    private String apiUrl;

    /** Clé API / consumer_key / access token (chiffré) */
    @Convert(converter = EncryptionConverter.class)
    @Column(name = "api_key", length = 512)
    private String apiKey;

    /** Secret / consumer_secret / mot de passe (chiffré) */
    @Convert(converter = EncryptionConverter.class)
    @Column(name = "api_secret", length = 512)
    private String apiSecret;

    // ── Planification ─────────────────────────────────────────────────────────

    /** Expression cron Spring (ex: "0 2 * * *" = chaque nuit à 2h) */
    @Column(name = "cron_expression", length = 100)
    private String cronExpression;

    /** Active/désactive le déclenchement automatique */
    @Column(name = "scheduling_enabled", nullable = false, columnDefinition = "boolean default false")
    private boolean schedulingEnabled = false;

    /** Expression cron pour la mise à jour prix/stock uniquement (ex: "0 6 * * *") */
    @Column(name = "price_cron_expression", length = 100)
    private String priceCronExpression;

    /** Active la mise à jour prix/stock automatique */
    @Column(name = "price_scheduling_enabled", nullable = false, columnDefinition = "boolean default false")
    private boolean priceSchedulingEnabled = false;

    // ── Boutique multi-tenant ─────────────────────────────────────────────────

    /** Si true, publie automatiquement les produits synchronisés en boutique */
    @Column(name = "publish_to_boutique", nullable = false, columnDefinition = "boolean default false")
    private boolean publishToBoutique = false;

    /** Identifiant du tenant/ESN pour le hub boutique (ex: slug de l'ESN) */
    @Column(name = "tenant_id", length = 100)
    private String tenantId;

    // ── Statut & synchronisation ──────────────────────────────────────────────

    private boolean active = true;

    @Column(name = "last_sync_at")
    private LocalDateTime lastSyncAt;

    @Column(name = "last_sync_status", length = 512)
    private String lastSyncStatus;

    @Column(name = "last_sync_count")
    private int lastSyncCount = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); }
}
