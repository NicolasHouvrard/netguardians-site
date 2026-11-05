package com.logicielpourtous.site.features.blog;

import com.logicielpourtous.site.features.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "blog_posts")
@Getter @Setter @NoArgsConstructor
public class BlogPost {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(unique = true)
    private String slug;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(columnDefinition = "TEXT")
    private String excerpt;

    @Enumerated(EnumType.STRING)
    private PostStatus status = PostStatus.DRAFT;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id")
    private User author;

    private String category;
    private String tags;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "deploy_linkedin")
    private boolean deployLinkedin = false;

    @Column(name = "deploy_twitter")
    private boolean deployTwitter = false;

    @Column(name = "deploy_facebook")
    private boolean deployFacebook = false;

    @Column(name = "view_count")
    private Integer viewCount = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); }

    public enum PostStatus {
        DRAFT("Brouillon"), PUBLISHED("Publié"), ARCHIVED("Archivé");
        public final String label;
        public final String badgeCss;
        PostStatus(String label) {
            this.label = label;
            this.badgeCss = switch(this.name()) {
                case "DRAFT"     -> "text-bg-secondary";
                case "PUBLISHED" -> "text-bg-success";
                case "ARCHIVED"  -> "text-bg-dark";
                default          -> "text-bg-secondary";
            };
        }
    }
}
