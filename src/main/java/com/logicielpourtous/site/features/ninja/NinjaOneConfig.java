package com.logicielpourtous.site.features.ninja;

import com.logicielpourtous.site.features.commun.EncryptionConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Configuration globale de l'intégration NinjaOne.
 * Une seule ligne en base (id = 1), mise à jour via l'interface admin.
 */
@Entity
@Table(name = "ninja_one_config")
@Getter @Setter
public class NinjaOneConfig {

    @Id
    private Long id = 1L;

    /** URL de base de l'instance NinjaOne (ex : https://eu.ninjarmm.com) */
    @Column(nullable = false)
    private String instanceUrl = "https://eu.ninjarmm.com";

    /** OAuth2 Client ID — stocké chiffré */
    @Convert(converter = EncryptionConverter.class)
    @Column(name = "client_id")
    private String clientId;

    /** OAuth2 Client Secret — stocké chiffré */
    @Convert(converter = EncryptionConverter.class)
    @Column(name = "client_secret")
    private String clientSecret;

    /** Dernière synchronisation réussie */
    private LocalDateTime lastSyncAt;

    /** Dernier message d'erreur de connexion */
    @Column(columnDefinition = "TEXT")
    private String lastError;

    private boolean enabled = false;
}
