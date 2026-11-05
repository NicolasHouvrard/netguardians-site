package com.logicielpourtous.site.features.licence;

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
 * Jeton d'installation a usage unique, genere par un {@link LicenceServeur} de role {@code PARTNER}
 * (via {@code POST /api/licence/jetons}) pour un de ses clients ({@code customerId}), et consomme
 * par ce client lors de son propre {@code POST /api/licence/enregistrer} (champ {@code jeton},
 * plutot que {@code login}/{@code motDePasse}).
 *
 * <p>C'est ce jeton — et non un champ du corps de la requete du client — qui porte le {@code
 * customerId} correct : le client final n'a donc jamais a connaitre ni saisir son propre {@code
 * customerId}, seulement a recevoir le jeton (hors perimetre de cette tache : canal de
 * transmission du jeton du partenaire vers l'installation client).
 *
 * <p>Pas de relation JPA vers {@link CompteLicence} ni {@code Customer} (meme choix que {@code
 * LicenceServeur.compteId}/{@code customerId}) : simples id, pas de couplage.
 */
@Entity
@Table(name = "jetons_client")
@Getter
@Setter
public class JetonClient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Compte de licence (MP) du {@code PARTNER} qui a genere ce jeton — c'est son quota qui sera
     * verifie lors de la consommation du jeton, jamais celui d'un autre compte. */
    private Long compteId;

    /** Id du {@code Customer} que representera le {@link LicenceServeur} cree a la consommation de
     * ce jeton. */
    private Long customerId;

    @Column(unique = true)
    private String code;

    private Instant expireA;

    private boolean utilise;

    private Instant creeA;

    @PrePersist
    protected void onCreate() {
        if (creeA == null) {
            creeA = Instant.now();
        }
    }
}
