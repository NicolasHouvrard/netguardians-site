package com.logicielpourtous.site.features.intra;

import com.logicielpourtous.site.features.module.AdminModule;
import jakarta.persistence.*;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "intra_widgets")
public class IntraWidget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "widget_key", unique = true, nullable = false)
    private String widgetKey;

    @Column(nullable = false)
    private String label;

    private String icon;
    private String color;
    private String link;

    /** Rôles autorisés, séparés par virgule : "ROLE_ADMIN,ROLE_COMPTABLE" */
    @Column(name = "allowed_roles")
    private String allowedRoles;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "module_id")
    private AdminModule module;

    private boolean active = true;

    @Column(name = "display_order")
    private Integer displayOrder = 10;

    public IntraWidget() {}

    public IntraWidget(String widgetKey, String label, String icon, String color,
                       String link, String allowedRoles, int displayOrder) {
        this.widgetKey    = widgetKey;
        this.label        = label;
        this.icon         = icon;
        this.color        = color;
        this.link         = link;
        this.allowedRoles = allowedRoles;
        this.displayOrder = displayOrder;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public List<String> getAllowedRolesList() {
        if (allowedRoles == null || allowedRoles.isBlank()) return List.of();
        return Arrays.asList(allowedRoles.split(","));
    }

    public boolean isAllowedFor(Set<String> roles) {
        return getAllowedRolesList().stream().anyMatch(roles::contains);
    }

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public Long getId()           { return id; }
    public String getWidgetKey()  { return widgetKey; }
    public String getLabel()      { return label; }
    public String getIcon()       { return icon; }
    public String getColor()      { return color; }
    public String getLink()       { return link; }
    public String getAllowedRoles(){ return allowedRoles; }
    public AdminModule getModule() { return module; }
    public boolean isActive()     { return active; }
    public Integer getDisplayOrder(){ return displayOrder; }

    public void setWidgetKey(String widgetKey)    { this.widgetKey = widgetKey; }
    public void setLabel(String label)            { this.label = label; }
    public void setIcon(String icon)              { this.icon = icon; }
    public void setColor(String color)            { this.color = color; }
    public void setLink(String link)              { this.link = link; }
    public void setAllowedRoles(String allowedRoles){ this.allowedRoles = allowedRoles; }
    public void setModule(AdminModule module)     { this.module = module; }
    public void setActive(boolean active)         { this.active = active; }
    public void setDisplayOrder(Integer displayOrder){ this.displayOrder = displayOrder; }
}
