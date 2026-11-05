package com.logicielpourtous.site.features.social;

import com.logicielpourtous.site.features.commun.Tenant;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "social_posts")
public class SocialPost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "image_path")
    private String imagePath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PostStatus status = PostStatus.DRAFT;

    @Column(name = "scheduled_at")
    private LocalDateTime scheduledAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "created_by_id")
    private Long createdById;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum PostStatus {
        DRAFT("Brouillon", "text-bg-secondary"),
        SCHEDULED("Planifié", "text-bg-info"),
        PUBLISHING("En cours", "text-bg-warning"),
        PUBLISHED("Publié", "text-bg-success"),
        PARTIAL("Partiel", "text-bg-warning"),
        FAILED("Échec", "text-bg-danger");

        private final String label;
        private final String badgeCss;

        PostStatus(String label, String badgeCss) {
            this.label    = label;
            this.badgeCss = badgeCss;
        }

        public String getLabel()    { return label; }
        public String getBadgeCss() { return badgeCss; }
    }

    // Getters / Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Tenant getTenant() { return tenant; }
    public void setTenant(Tenant tenant) { this.tenant = tenant; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getImagePath() { return imagePath; }
    public void setImagePath(String imagePath) { this.imagePath = imagePath; }
    public PostStatus getStatus() { return status; }
    public void setStatus(PostStatus status) { this.status = status; }
    public LocalDateTime getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(LocalDateTime scheduledAt) { this.scheduledAt = scheduledAt; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }
    public Long getCreatedById() { return createdById; }
    public void setCreatedById(Long createdById) { this.createdById = createdById; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
