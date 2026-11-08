package com.logicielpourtous.site.features.identite;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Ligne du registre unique des societes (voir {@code V4__registre_societes.sql}). {@code siren} est nul pour
 * une societe etrangere ; {@code proprietaireTenantId} nul signifie que la societe appartient au Maitre.
 */
@Entity
@Table(name = "societes_identites", schema = "public")
@Getter
@Setter
public class SocieteIdentite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "siren")
    private String siren;

    @Column(name = "tva", nullable = false)
    private String tva;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private TypeSociete type;

    @Column(name = "proprietaire_tenant_id")
    private Long proprietaireTenantId;

    @Column(name = "fiche_type")
    private String ficheType;

    @Column(name = "fiche_id")
    private Long ficheId;

    @Column(name = "cree_a", nullable = false)
    private Instant creeA;

    @PrePersist
    protected void onCreate() {
        if (creeA == null) {
            creeA = Instant.now();
        }
    }
}
