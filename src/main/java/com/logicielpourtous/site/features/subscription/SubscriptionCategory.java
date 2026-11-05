package com.logicielpourtous.site.features.subscription;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "subscription_categories")
@Getter @Setter
public class SubscriptionCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String label;

    private String icon     = "fas fa-box";
    private String badgeCss = "text-bg-secondary";

    @Column(name = "sort_order")
    private int sortOrder = 0;

    private boolean active = true;
}
