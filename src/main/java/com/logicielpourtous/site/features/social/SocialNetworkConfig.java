package com.logicielpourtous.site.features.social;

import com.logicielpourtous.site.features.commun.EncryptionConverter;
import com.logicielpourtous.site.features.commun.Tenant;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "social_network_configs")
public class SocialNetworkConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Enumerated(EnumType.STRING)
    @Column(name = "network_type", nullable = false, length = 20)
    private SocialNetworkType networkType;

    @Column(nullable = false, length = 100)
    private String name;

    @Convert(converter = EncryptionConverter.class)
    @Column(name = "client_id", length = 512)
    private String clientId;

    @Convert(converter = EncryptionConverter.class)
    @Column(name = "client_secret", length = 512)
    private String clientSecret;

    @Convert(converter = EncryptionConverter.class)
    @Column(name = "access_token", length = 1024)
    private String accessToken;

    @Convert(converter = EncryptionConverter.class)
    @Column(name = "refresh_token", length = 1024)
    private String refreshToken;

    @Column(name = "token_expires_at")
    private LocalDateTime tokenExpiresAt;

    @Column(name = "page_id", length = 100)
    private String pageId;

    @Column(name = "account_handle", length = 100)
    private String accountHandle;

    @Column(columnDefinition = "boolean default true")
    private boolean active = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public boolean isConnected() {
        return accessToken != null && !accessToken.isBlank();
    }

    public boolean isTokenExpired() {
        return tokenExpiresAt != null && tokenExpiresAt.isBefore(LocalDateTime.now());
    }

    // Getters / Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Tenant getTenant() { return tenant; }
    public void setTenant(Tenant tenant) { this.tenant = tenant; }
    public SocialNetworkType getNetworkType() { return networkType; }
    public void setNetworkType(SocialNetworkType networkType) { this.networkType = networkType; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
    public LocalDateTime getTokenExpiresAt() { return tokenExpiresAt; }
    public void setTokenExpiresAt(LocalDateTime tokenExpiresAt) { this.tokenExpiresAt = tokenExpiresAt; }
    public String getPageId() { return pageId; }
    public void setPageId(String pageId) { this.pageId = pageId; }
    public String getAccountHandle() { return accountHandle; }
    public void setAccountHandle(String accountHandle) { this.accountHandle = accountHandle; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
