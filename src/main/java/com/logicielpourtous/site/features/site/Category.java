package com.logicielpourtous.site.features.site;


import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import lombok.Setter;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;


@Entity
@Getter @Setter
@NoArgsConstructor
@Table(name = "category")
public class Category implements Navigable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private Integer position; // Pour l'ordre dans le Header (1, 2, 3...)

    private Boolean active = true;

   

    @OneToMany(mappedBy = "category")
    @OrderBy("displayOrder ASC") // Trie les pages auto au sein de la catégorie
    private List<Page> pages = new java.util.ArrayList<>();

    public  Category(Long id, String name, Integer position, boolean active,  List<Page> pages){
        this.id = id;
        this.name = name;
        this.position = position;
        this.active = active;
        this.pages = pages;
    }

     public String getMenuType() { return "CATEGORY"; }

    @Override
    public int getOrder() { return this.position; }
    @Override
    public void setOrder(int order) { this.position = order; }
}
