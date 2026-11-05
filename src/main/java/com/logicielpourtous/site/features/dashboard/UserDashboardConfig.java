package com.logicielpourtous.site.features.dashboard;

import jakarta.persistence.*;

@Entity
@Table(name = "user_dashboard_configs")
public class UserDashboardConfig {

    @Id
    private Long userId;

    /** Widgets portail client */
    @Column(columnDefinition = "TEXT")
    private String widgetCodes = "";

    /** Widgets accueil ESN (/intra) */
    @Column(name = "intra_widget_codes", columnDefinition = "TEXT")
    private String intraWidgetCodes = "";

    /** Bandeau affiché ou réduit */
    private boolean bannerVisible = true;

    public Long    getUserId()       { return userId; }
    public String  getWidgetCodes()  { return widgetCodes; }
    public boolean isBannerVisible() { return bannerVisible; }

    public void setUserId(Long v)        { userId        = v; }
    public void setWidgetCodes(String v)      { widgetCodes      = v != null ? v : ""; }
    public void setIntraWidgetCodes(String v) { intraWidgetCodes = v != null ? v : ""; }
    public String getIntraWidgetCodes()       { return intraWidgetCodes != null ? intraWidgetCodes : ""; }
    public void setBannerVisible(boolean v) { bannerVisible = v; }
}
