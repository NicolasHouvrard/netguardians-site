package com.logicielpourtous.site.features.clients;

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
 * Lien d'un client vers son partenaire referent (un seul par client). {@code origine} vaut {@code PARTENAIRE}
 * (cree par le partenaire) ou {@code INSCRIPTION} (inscription autonome). Le compteur de changements porte sur
 * {@code anneeReference}.
 */
@Entity
@Table(name = "liens_clients", schema = "public")
@Getter
@Setter
public class LienClient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_societe_id", nullable = false, unique = true)
    private Long clientSocieteId;

    @Column(name = "partenaire_societe_id", nullable = false)
    private Long partenaireSocieteId;

    @Column(name = "origine", nullable = false, length = 20)
    private String origine;

    @Column(name = "depuis", nullable = false)
    private Instant depuis;

    @Column(name = "changements_annee_courante", nullable = false)
    private int changementsAnneeCourante;

    @Column(name = "annee_reference")
    private Integer anneeReference;

    @PrePersist
    protected void onCreate() {
        if (depuis == null) {
            depuis = Instant.now();
        }
    }
}
