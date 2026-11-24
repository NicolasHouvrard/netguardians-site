package com.logicielpourtous.site.features.theme;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Une valeur de theme qui remplace la valeur par defaut. Pas de ligne = valeur heritee. */
@Entity
@Table(name = "theme_reglages", schema = "public")
@Getter
@NoArgsConstructor
public class ThemeReglage {

    @Id
    @Column(name = "cle", length = 60)
    private String cle;

    @Column(name = "valeur", nullable = false, length = 40)
    private String valeur;

    @Column(name = "modifie_par")
    private Long modifiePar;

    @Column(name = "modifie_a", nullable = false)
    private Instant modifieA;

    public ThemeReglage(String cle, String valeur, Long modifiePar) {
        this.cle = cle;
        this.valeur = valeur;
        this.modifiePar = modifiePar;
        this.modifieA = Instant.now();
    }

    void remplacer(String nouvelleValeur, Long admin) {
        this.valeur = nouvelleValeur;
        this.modifiePar = admin;
        this.modifieA = Instant.now();
    }
}
