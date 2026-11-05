package  com.logicielpourtous.site.features.module;


import java.util.ArrayList;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter @Setter 
@NoArgsConstructor 
@Table(name = "admin_modules")
public class AdminModule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String title;
    private String icon;
    private String color;
    private String url;
    @Column(name = "role_required")
    private String roleRequired;
    private String category;   
    
    // Nouvelle colonne pour le groupement
   

    @Column(name = "display_order")
    private Integer displayOrder;

    @Column(columnDefinition = "TEXT")
    private String description;

    private boolean active = true;

    /** Code catégorie d'abonnement requis (null = accès libre) */
    @Column(name = "subscription_code")
    private String subscriptionCode;

    /** Public cible : ESN, CLIENT, ALL */
    private String audience = "ESN";
}