package com.logicielpourtous.site.features.auth;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Demande faite par quelqu'un qui a tente d'inscrire une societe deja connue du registre (voir
 * {@code V6__inscription_autonome.sql}). On garde une trace sans reveler a l'interesse laquelle ; {@code traitee}
 * passe a vrai quand un responsable l'a prise en charge.
 */
@Entity
@Table(name = "demandes_societe_connue", schema = "public")
@Getter
@Setter
public class DemandeSocieteConnue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "societe_identite_id", nullable = false)
    private Long societeIdentiteId;

    @Column(name = "email_demandeur", nullable = false)
    private String emailDemandeur;

    @Column(name = "nom_demandeur")
    private String nomDemandeur;

    @Column(name = "cree_a", nullable = false)
    private Instant creeA;

    @Column(name = "traitee", nullable = false)
    private boolean traitee;

    @PrePersist
    protected void onCreate() {
        if (creeA == null) {
            creeA = Instant.now();
        }
    }
}
