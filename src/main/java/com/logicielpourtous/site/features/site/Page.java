package com.logicielpourtous.site.features.site;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "pages" )
public class Page implements Navigable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(unique = true, nullable = false)
    private String slug; // Ex: "a-propos"

    @Column(columnDefinition = "TEXT")
    private String content; // Stocke le HTML ou le texte

    @Column(name = "is_published")
    private Boolean isPublished = false;

    @Column(name = "is_home_page")
    private Boolean isHomePage = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = true)
    private Category category;

    @Column(name = "is_active")
    private  Boolean isActive = false;

    @Column(name = "display_order")
    private  Integer displayOrder;

    public Boolean isHomePage() {
        return isHomePage;
    }

    public void setIsHomePage(Boolean isHomePage) {
        this.isHomePage = isHomePage;
    }
    
    public Boolean getIsHomePage() {
        return isHomePage;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Boolean getIsPublished() {
        return isPublished;
    }

    public void setIsPublished(Boolean isPublished) {
        this.isPublished = isPublished;
    }

    // Getters et Setters
  
    
    public void  setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }
    public Boolean getIsActive() {return this.isActive;}

    public void  setDisplayOrder(Integer displayOrder) {
        this.displayOrder = displayOrder;
    }
    public Integer getDisplayOrder() {return this.displayOrder;}


// Le Getter doit renvoyer Category
public Category getCategory() {
    return category;
}

// Le Setter doit accepter Category
public void setCategory(Category category) {
    this.category = category;
}

public String getMenuType() { return "PAGE"; }

    @Override
    public int getOrder() { return this.displayOrder; }
    @Override
    public void setOrder(int order) { this.displayOrder = order; }
}