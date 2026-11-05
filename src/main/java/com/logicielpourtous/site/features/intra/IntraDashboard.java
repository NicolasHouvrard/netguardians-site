package com.logicielpourtous.site.features.intra;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "intra_dashboards")
@Getter @Setter @NoArgsConstructor
public class IntraDashboard {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nom affiché dans l'onglet */
    private String name;

    /** Identifiant URL (ex: "commercial", "mon-dashboard") */
    @Column(unique = true)
    private String slug;

    /** Icône FontAwesome (ex: "fas fa-handshake") */
    private String icon = "fas fa-gauge-high";

    /** Couleur hex de l'icône */
    private String color = "#6366f1";

    /** Ordre d'affichage dans la barre de navigation */
    @Column(name = "display_order")
    private Integer displayOrder = 10;

    /** Actif / masqué */
    private boolean active = true;

    /** Rôle requis pour voir cet onglet (null = tous les rôles ESN) */
    @Column(name = "role_required")
    private String roleRequired;

    /** Modules assignés à ce dashboard avec leur sous-catégorie */
    @OneToMany(mappedBy = "dashboard", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC NULLS LAST")
    private List<DashboardModuleLink> moduleLinks = new ArrayList<>();

    /** Configuration des widgets de ce dashboard */
    @OneToMany(mappedBy = "dashboard", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<DashboardWidgetLayout> widgetLayouts = new ArrayList<>();
}
