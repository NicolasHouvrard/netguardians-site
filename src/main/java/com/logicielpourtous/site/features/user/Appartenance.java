package com.logicielpourtous.site.features.user;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.role.Role;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Appartenance d'une personne a une societe (voir {@code V5__appartenances.sql}) : le tenant de la societe,
 * la fiche client de la personne dans cette societe ({@code customerId}, nul si elle n'en a pas) et ses
 * roles dans cette societe.
 */
@Entity
@Table(name = "appartenances", schema = "public")
@Getter
@Setter
public class Appartenance implements java.io.Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(name = "customer_id")
    private Long customerId;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "appartenances_roles",
        joinColumns = @JoinColumn(name = "appartenance_id"),
        inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    private Set<Role> roles = new HashSet<>();

    @Column(name = "cree_a", nullable = false)
    private Instant creeA;

    @PrePersist
    protected void onCreate() {
        if (creeA == null) {
            creeA = Instant.now();
        }
    }
}
