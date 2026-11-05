package com.logicielpourtous.site.features.sync;

import java.time.Instant;

import com.logicielpourtous.site.features.commun.ServerRole;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Serveur secondaire (role {@code CLIENT} ou {@code PARTNER}) enrole auprès de ce maitre.
 *
 * <p>Entite purement locale : contrairement a {@code Request}, elle n'etend PAS
 * {@code Syncable} et ne traverse jamais le reseau de synchronisation elle-meme.
 */
@Entity
@Table(name = "secondary_servers")
@Getter
@Setter
public class SecondaryServer {

    public enum SecondaryServerStatut {
        ACTIF,
        REVOQUE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nom;

    @Enumerated(EnumType.STRING)
    private ServerRole role;

    /**
     * Id du {@code Customer} (module {@code customers}) que represente ce serveur secondaire : il ne
     * recoit QUE les {@code Request} de ce client, et ne peut modifier QUE celles-ci (verifie a la
     * reception). Simple id, pas une relation JPA : evite une dependance Modulith {@code sync -> customers}
     * (meme patron que {@code Request.assignedTeamDisplayName()}). {@code null} = rattache a aucun
     * client : ne recoit rien, et tout push est refuse (403).
     */
    private Long customerId;

    /**
     * Correlation metadata ecrite/lue UNIQUEMENT par {@code features.licence.LicenceDecouverteScheduler}
     * (Task 6, fix round 1) : l'{@code identifiantInstallationStable} du {@code LicenceServeur} (module
     * {@code licence}) decouvert qui a materialise CETTE ligne. {@code customerId} seul ne suffit PAS a
     * identifier une installation de facon stable — un meme client peut etre reinstalle (machine
     * remplacee) avec un NOUVEL {@code identifiantInstallationStable} sous le MEME {@code customerId},
     * pendant qu'une ancienne installation reste {@code REVOQUE} : matcher par {@code customerId} seul
     * reactiverait a tort l'ancienne ligne (et son {@code apiKeyHash}, valide pour la machine morte).
     * {@code null} pour un {@code SecondaryServer} cree par tout autre moyen (ecran admin {@code
     * GestionServeursController}, ou materialise par une version anterieure a ce fix) — {@code
     * LicenceDecouverteScheduler} traite ce cas comme "a re-identifier", voir sa javadoc.
     *
     * <p><b>Jamais lu ni ecrit</b> par le protocole de synchronisation existant ({@code
     * RequestSyncApplier}, {@code SyncOutboxScheduler}, {@code EnrollmentController}, detection de
     * conflit...) — purement informatif pour le module {@code licence}, meme categorie que {@code
     * SyncConflict.resolu}.
     */
    private String identifiantInstallationStable;

    /** Code a usage unique, efface (mis a null) une fois l'enrolement consomme. */
    private String enrollmentCode;

    private Instant enrollmentCodeExpiresAt;

    /** Empreinte de la cle API, remplie apres l'enrolement. La cle en clair n'est jamais stockee. */
    private String apiKeyHash;

    @Enumerated(EnumType.STRING)
    private SecondaryServerStatut statut = SecondaryServerStatut.ACTIF;

    private Instant creeA;

    private Instant misAJourA;

    @PrePersist
    protected void onCreate() {
        if (statut == null) {
            statut = SecondaryServerStatut.ACTIF;
        }
        Instant maintenant = Instant.now();
        creeA = maintenant;
        misAJourA = maintenant;
    }

    @PreUpdate
    protected void onUpdate() {
        misAJourA = Instant.now();
    }
}
