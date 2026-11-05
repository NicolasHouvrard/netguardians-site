package com.logicielpourtous.site.features.role;

import jakarta.persistence.*;

@Entity
@Table(name = "roles")
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String name;   // ex: "ROLE_TECHNICIEN"

    @Column
    private String label;  // ex: "Technicien"

    /** "ESN" pour les rôles internes, "CLIENT" pour les rôles portail */
    @Column
    private String type;

    public Role() {}

    public Role(String name) {
        this.name  = name;
        this.label = name;
    }

    public Role(String name, String label, String type) {
        this.name  = name;
        this.label = label;
        this.type  = type;
    }

    public Long   getId()    { return id; }
    public String getName()  { return name; }
    public String getLabel() { return label != null ? label : name; }
    public String getType()  { return type; }

    public void setName(String name)   { this.name = name; }
    public void setLabel(String label) { this.label = label; }
    public void setType(String type)   { this.type = type; }
}
