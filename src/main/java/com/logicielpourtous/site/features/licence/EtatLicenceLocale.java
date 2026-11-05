package com.logicielpourtous.site.features.licence;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Etat de licence de CETTE installation (serveur {@code PARTNER} ou {@code CLIENT}), cote
 * secondaire — singleton logique (une seule ligne en pratique, creee au premier demarrage par
 * {@link LicenceBootstrap}), comme {@code app.sync.api-key} l'est pour le moteur de synchronisation
 * deja livre, mais persiste en base plutot qu'en proprietes (pas de copie manuelle a faire).
 *
 * <p>{@code cleApiLicence} est stockee EN CLAIR (lue ensuite par d'autres composants, comme
 * {@code RestSyncClient} lit {@code app.sync.api-key} en clair) — contrairement a
 * {@link LicenceServeur#getCleApiHash()} cote MAITRE, qui ne conserve qu'une empreinte : ici c'est
 * la cle elle-meme qui doit etre reutilisee par CE serveur pour s'authentifier auprès du maitre.
 */
@Entity
@Table(name = "etat_licence_locale")
@Getter
@Setter
public class EtatLicenceLocale {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** UUID genere par cette installation au premier demarrage, stable a travers redemarrages. */
    private String identifiantInstallationStable;

    /** Cle API licence en clair, obtenue via {@code POST /api/licence/cle} ; {@code null} avant
     * l'enrolement. */
    private String cleApiLicence;

    /** Adresse du serveur PARTNER resolu pour ce CLIENT ; rempli par {@link LicenceBootstrap} (Task
     * 6) une fois que le partenaire a pousse le code local (voir {@code
     * LicenceEnrolementController#reponseEnregistrement}). Toujours {@code null} pour un
     * {@code PARTNER}. */
    private String adressePartenaireResolue;

    /** Code d'enrolement du {@code SecondaryServer} local materialise par le PARTENAIRE pour CE
     * client (Task 6) : presente par {@link LicenceBootstrap} au {@code POST /api/sync/enroll} de
     * l'adresse ci-dessus (fix round 1) pour terminer SA PROPRE inscription au moteur de
     * synchronisation. {@code null} pour un {@code PARTNER}, ou pour un {@code CLIENT} dont le
     * partenaire n'a pas encore pousse de code. */
    private String codeLocalPartenaire;

    /** Cle API du moteur de synchronisation {@code features.sync} EN CLAIR, obtenue automatiquement
     * par {@link LicenceBootstrap} (Task 6, fix round 1) via {@code POST /api/sync/enroll} une fois
     * {@code codeLocalPartenaire} disponible. Jamais ecrite dans {@code app.sync.api-key} ni lue par
     * {@code RestSyncClient} : seulement STOCKEE ici, a copier manuellement dans la configuration
     * avant de redemarrer (meme restriction "pas de redemarrage programmatique" que {@code
     * cleApiLicence}). {@code null} pour un {@code PARTNER}, ou avant cette inscription. */
    private String cleApiSync;

    private boolean enregistrementTermine;

    private Instant creeA;

    @PrePersist
    protected void onCreate() {
        if (creeA == null) {
            creeA = Instant.now();
        }
    }
}
