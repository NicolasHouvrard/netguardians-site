package com.logicielpourtous.site.features.intra;

import com.logicielpourtous.site.features.module.AdminModule;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "dashboard_module_links",
       uniqueConstraints = @UniqueConstraint(columnNames = {"dashboard_id", "module_id"}))
@Getter @Setter @NoArgsConstructor
public class DashboardModuleLink {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dashboard_id", nullable = false)
    private IntraDashboard dashboard;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "module_id", nullable = false)
    private AdminModule module;

    /** Groupe dans le méga-menu (ex: "Clients", "Ventes", "Facturation") */
    @Column(name = "sub_category")
    private String subCategory;

    /** Ordre d'affichage dans le groupe */
    @Column(name = "display_order")
    private Integer displayOrder;
}
