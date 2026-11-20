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

/** Demande de changement de partenaire en attente de decision de l'admin ({@code EN_ATTENTE}, {@code ACCEPTEE}, {@code REFUSEE}). */
@Entity
@Table(name = "demandes_changement_partenaire", schema = "public")
@Getter
@Setter
public class DemandeChangementPartenaire {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_societe_id", nullable = false)
    private Long clientSocieteId;

    @Column(name = "nouveau_partenaire_societe_id", nullable = false)
    private Long nouveauPartenaireSocieteId;

    @Column(name = "motif")
    private String motif;

    @Column(name = "cree_a", nullable = false)
    private Instant creeA;

    @Column(name = "statut", nullable = false, length = 20)
    private String statut;

    @PrePersist
    protected void onCreate() {
        if (creeA == null) {
            creeA = Instant.now();
        }
    }
}
