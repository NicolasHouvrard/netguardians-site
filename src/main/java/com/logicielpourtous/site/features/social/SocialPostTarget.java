package com.logicielpourtous.site.features.social;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "social_post_targets")
public class SocialPostTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", nullable = false)
    private SocialPost post;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "network_config_id", nullable = false)
    private SocialNetworkConfig networkConfig;

    @Column(name = "custom_content", columnDefinition = "TEXT")
    private String customContent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TargetStatus status = TargetStatus.PENDING;

    @Column(name = "external_post_id", length = 255)
    private String externalPostId;

    @Column(columnDefinition = "TEXT")
    private String error;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(nullable = false)
    private int attempts = 0;

    public enum TargetStatus {
        PENDING("En attente", "text-bg-secondary"),
        PUBLISHED("Publié", "text-bg-success"),
        FAILED("Échec", "text-bg-danger"),
        SKIPPED("Ignoré", "text-bg-warning");

        private final String label;
        private final String badgeCss;

        TargetStatus(String label, String badgeCss) {
            this.label    = label;
            this.badgeCss = badgeCss;
        }

        public String getLabel()    { return label; }
        public String getBadgeCss() { return badgeCss; }
    }

    // Getters / Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public SocialPost getPost() { return post; }
    public void setPost(SocialPost post) { this.post = post; }
    public SocialNetworkConfig getNetworkConfig() { return networkConfig; }
    public void setNetworkConfig(SocialNetworkConfig networkConfig) { this.networkConfig = networkConfig; }
    public String getCustomContent() { return customContent; }
    public void setCustomContent(String customContent) { this.customContent = customContent; }
    public TargetStatus getStatus() { return status; }
    public void setStatus(TargetStatus status) { this.status = status; }
    public String getExternalPostId() { return externalPostId; }
    public void setExternalPostId(String externalPostId) { this.externalPostId = externalPostId; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
}
