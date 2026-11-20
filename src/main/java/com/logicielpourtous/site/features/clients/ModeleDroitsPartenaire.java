package com.logicielpourtous.site.features.clients;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Un droit donne par defaut a un nouveau partenaire. */
@Entity
@Table(name = "modele_droits_partenaire", schema = "public")
@Getter
@NoArgsConstructor
public class ModeleDroitsPartenaire {

    @Id
    @Column(name = "droit", nullable = false, length = 60)
    private String droit;

    public ModeleDroitsPartenaire(String droit) {
        this.droit = droit;
    }
}
