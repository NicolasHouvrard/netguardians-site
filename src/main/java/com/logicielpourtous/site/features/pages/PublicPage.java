package com.logicielpourtous.site.features.pages;

import com.logicielpourtous.site.features.user.User;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "public_pages", schema = "public")
public class PublicPage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String slug;

    @Column(nullable = false)
    private String title;

    @Column(name = "meta_title")
    private String metaTitle;

    @Column(name = "meta_description", columnDefinition = "TEXT")
    private String metaDescription;

    @Column(name = "meta_keywords")
    private String metaKeywords;

    @Column(name = "meta_og_image")
    private String metaOgImage;

    @Column(nullable = false)
    private boolean published = false;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "page_type", nullable = false)
    private PageType pageType = PageType.PAGE;

    @Column(columnDefinition = "TEXT")
    private String excerpt;

    @Column(name = "featured_image_url")
    private String featuredImageUrl;

    @Column(name = "home_page")
    private Boolean homePage = false;

    @Column(name = "blog_category")
    private String blogCategory;

    @Column(name = "blog_tags")
    private String blogTags;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id")
    private User author;

    @OneToMany(mappedBy = "page", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    private List<PageBlock> blocks = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Getters & Setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getMetaTitle() { return metaTitle; }
    public void setMetaTitle(String metaTitle) { this.metaTitle = metaTitle; }

    public String getMetaDescription() { return metaDescription; }
    public void setMetaDescription(String metaDescription) { this.metaDescription = metaDescription; }

    public String getMetaKeywords() { return metaKeywords; }
    public void setMetaKeywords(String metaKeywords) { this.metaKeywords = metaKeywords; }

    public String getMetaOgImage() { return metaOgImage; }
    public void setMetaOgImage(String metaOgImage) { this.metaOgImage = metaOgImage; }

    public boolean isPublished() { return published; }
    public void setPublished(boolean published) { this.published = published; }

    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public PageType getPageType() { return pageType; }
    public void setPageType(PageType pageType) { this.pageType = pageType; }

    public String getExcerpt() { return excerpt; }
    public void setExcerpt(String excerpt) { this.excerpt = excerpt; }

    public String getFeaturedImageUrl() { return featuredImageUrl; }
    public void setFeaturedImageUrl(String featuredImageUrl) { this.featuredImageUrl = featuredImageUrl; }

    public String getBlogCategory() { return blogCategory; }
    public void setBlogCategory(String blogCategory) { this.blogCategory = blogCategory; }

    public String getBlogTags() { return blogTags; }
    public void setBlogTags(String blogTags) { this.blogTags = blogTags; }

    public User getAuthor() { return author; }
    public void setAuthor(User author) { this.author = author; }

    public boolean isHomePage() { return Boolean.TRUE.equals(homePage); }
    public void setHomePage(boolean homePage) { this.homePage = homePage; }

    public List<PageBlock> getBlocks() { return blocks; }
    public void setBlocks(List<PageBlock> blocks) { this.blocks = blocks; }
}
