package com.logicielpourtous.site.features.licence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Reglages de federation du Maitre : ligne unique ({@code id = 1}, creee par la migration V3).
 * {@code serveurDefautId} : serveur d'accueil attribue aux nouveaux tenants ({@code null} = aucun,
 * les nouveaux tenants restent rattaches au Maitre).
 */
@Entity
@Table(name = "parametres_federation", schema = "public")
@Getter
@Setter
public class ParametreFederation {

    public static final long ID_UNIQUE = 1L;

    @Id
    private Long id = ID_UNIQUE;

    @Column(name = "serveur_defaut_id")
    private Long serveurDefautId;
}
