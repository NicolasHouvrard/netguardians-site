package com.logicielpourtous.site.features.auth;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Ligne unique (id = 1) des reglages du compte unique de ce serveur (voir {@code V20__reglages_compte_unique.sql}).
 * {@code clientSecret} contient le secret DEJA CHIFFRE ; le {@code toString} ne montre aucune valeur.
 */
@Entity
@Table(name = "reglages_compte_unique", schema = "public")
@Getter
@Setter
public class ReglagesCompteUnique {

    public static final long ID_UNIQUE = 1L;

    @Id
    private Long id = ID_UNIQUE;

    @Column(name = "issuer_uri")
    private String issuerUri;

    @Column(name = "client_id")
    private String clientId;

    /** Chiffre (AES, cle de l'application). */
    @Column(name = "client_secret")
    private String clientSecret;

    @Column(name = "url_publique")
    private String urlPublique;

    @Column(name = "maitre_url_publique")
    private String maitreUrlPublique;

    @Column(name = "retour_deconnexion")
    private String retourDeconnexion;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "modifie_par")
    private String modifiePar;

    @Column(name = "modifie_a", nullable = false)
    private Instant modifieA = Instant.now();

    @Override
    public String toString() {
        return "ReglagesCompteUnique[id=" + id + ", version=" + version + "]";
    }
}
