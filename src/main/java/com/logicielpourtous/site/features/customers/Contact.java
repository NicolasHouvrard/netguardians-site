package com.logicielpourtous.site.features.customers;

import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "contacts") // Pas de schéma spécifié, Hibernate utilisera le tenant courant
@Getter @Setter
public class Contact {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private String position;

    @ManyToOne
    @JoinColumn(name = "customer_id")
    private Customer customer;
    
    // Ce champ fait le pont avec l'utilisateur dans le schéma public
    private Long userId; 

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "site_id")
    private Site site;


    public String getFullName() {
    return (firstName != null ? firstName : "") + " " + (lastName != null ? lastName : "");
}
}
