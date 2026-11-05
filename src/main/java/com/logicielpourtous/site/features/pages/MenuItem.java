package com.logicielpourtous.site.features.pages;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "menu_items", schema = "public")
public class MenuItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String label;

    @Column
    private String url;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "page_id")
    private PublicPage linkedPage;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private MenuItem parent;

    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL)
    @OrderBy("sortOrder ASC")
    private List<MenuItem> children = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "menu_position")
    private MenuPosition position = MenuPosition.MAIN;

    @Column
    private String target = "_self";

    @Column(name = "sort_order")
    private int sortOrder = 0;

    @Column
    private boolean active = true;

    /**
     * Returns the effective URL for this menu item.
     * Priority: explicit url → linked page slug → "#"
     */
    public String getResolvedUrl() {
        if (url != null && !url.isBlank()) {
            return url;
        }
        if (linkedPage != null) {
            return "/" + linkedPage.getSlug();
        }
        return "#";
    }

    // Getters & Setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public PublicPage getLinkedPage() { return linkedPage; }
    public void setLinkedPage(PublicPage linkedPage) { this.linkedPage = linkedPage; }

    public MenuItem getParent() { return parent; }
    public void setParent(MenuItem parent) { this.parent = parent; }

    public List<MenuItem> getChildren() { return children; }
    public void setChildren(List<MenuItem> children) { this.children = children; }

    public MenuPosition getPosition() { return position; }
    public void setPosition(MenuPosition position) { this.position = position; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
