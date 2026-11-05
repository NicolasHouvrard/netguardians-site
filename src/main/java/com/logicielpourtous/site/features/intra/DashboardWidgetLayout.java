package com.logicielpourtous.site.features.intra;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "dashboard_widget_layouts")
@Getter @Setter @NoArgsConstructor
public class DashboardWidgetLayout {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dashboard_id", nullable = false)
    private IntraDashboard dashboard;

    /** Clé du widget — correspond à IntraWidget.widgetKey */
    @Column(name = "widget_type", nullable = false)
    private String widgetType;

    /** Position d'affichage (1 = premier) */
    private Integer position = 1;

    /**
     * Taille Bootstrap legacy (colonnes sur 12).
     * Remplacé par gsW pour les dashboards Gridstack.
     */
    @Column(name = "col_size")
    private Integer colSize = 3;

    // ── Gridstack position / taille ───────────────────────────────────────────

    /** Colonne de départ Gridstack (0–11) */
    @Column(name = "gs_x")
    private Integer gsX;

    /** Ligne de départ Gridstack (auto-placement si null) */
    @Column(name = "gs_y")
    private Integer gsY;

    /** Largeur en colonnes Gridstack (1–12) */
    @Column(name = "gs_w")
    private Integer gsW;

    /** Hauteur en unités de grille Gridstack */
    @Column(name = "gs_h")
    private Integer gsH;
}
