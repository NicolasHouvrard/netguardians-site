package com.logicielpourtous.site.features.theme;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Une ligne du journal des modifications du theme (valeur nulle = valeur heritee). */
@Entity
@Table(name = "theme_journal", schema = "public")
@Getter
@NoArgsConstructor
public class ThemeJournal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cle", nullable = false, length = 60)
    private String cle;

    @Column(name = "ancienne_valeur", length = 40)
    private String ancienneValeur;

    @Column(name = "nouvelle_valeur", length = 40)
    private String nouvelleValeur;

    @Column(name = "modifie_par")
    private Long modifiePar;

    @Column(name = "cree_a", nullable = false)
    private Instant creeA;

    public ThemeJournal(String cle, String ancienneValeur, String nouvelleValeur, Long modifiePar) {
        this.cle = cle;
        this.ancienneValeur = ancienneValeur;
        this.nouvelleValeur = nouvelleValeur;
        this.modifiePar = modifiePar;
        this.creeA = Instant.now();
    }
}
