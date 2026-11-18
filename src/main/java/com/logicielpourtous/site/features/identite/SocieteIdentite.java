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

    /** Nul pour un particulier (voir {@code V12__particuliers_registre.sql}). */
    @Column(name = "tva")
    private String tva;

    /** Client particulier : ni SIREN ni TVA. */
    @Column(name = "particulier", nullable = false)
    private boolean particulier;

    @Enumerated(EnumType.STRING)
    /** Historique : n'est plus ecrit par le nouveau code (voir les capacites, {@code V11__capacites_clients.sql}). */
    @Column(name = "type")
    private TypeSociete type;

    @Column(name = "proprietaire_tenant_id")
    private Long proprietaireTenantId;

    @Column(name = "fiche_type")
    private String ficheType;

    @Column(name = "fiche_id")
    private Long ficheId;

    /**
     * Origine de la ligne : {@code INSCRIPTION_CLIENT} pour un client inscrit seul (seul cas qu'un nettoyage peut
     * liberer) ; {@code null} pour tout le reste (reprise de l'existant, partenaire approuve...).
     */
    @Column(name = "origine", length = 30)
    private String origine;

    @Column(name = "cree_a", nullable = false)
    private Instant creeA;

    @PrePersist
    protected void onCreate() {
        if (creeA == null) {
            creeA = Instant.now();
        }
    }
}
