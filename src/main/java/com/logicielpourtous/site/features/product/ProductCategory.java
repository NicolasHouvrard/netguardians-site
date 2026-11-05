package com.logicielpourtous.site.features.product;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "product_categories")
@Getter @Setter @NoArgsConstructor
public class ProductCategory {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String icon  = "fas fa-tag";
    private String color = "#64748b";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private ProductCategory parent;

    @OneToMany(mappedBy = "parent", fetch = FetchType.LAZY)
    @OrderBy("name ASC")
    private List<ProductCategory> children = new ArrayList<>();

    @OneToMany(mappedBy = "category", fetch = FetchType.LAZY)
    private List<Product> products = new ArrayList<>();

    public boolean isRoot() { return parent == null; }
}
