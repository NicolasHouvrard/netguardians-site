package com.logicielpourtous.site.features.clients;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Etat d'un partenaire : ACTIF, LIMITE ou BLOQUE. */
@Entity
@Table(name = "partenaires_etat", schema = "public")
@Getter
@Setter
@NoArgsConstructor
public class EtatPartenaire {

    @Id
    @Column(name = "societe_id", nullable = false)
    private Long societeId;

    @Column(name = "etat", nullable = false, length = 20)
    private String etat;

    @Column(name = "debut_validation", nullable = false)
    private Instant debutValidation;
}
