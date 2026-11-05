package com.logicielpourtous.site.features.licence;

import java.time.Instant;

import com.logicielpourtous.site.features.commun.ServerRole;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * Une installation (serveur secondaire {@code PARTNER} ou {@code CLIENT}) enrolee auprès de ce
 * maitre via le mecanisme de licence (login/mot de passe d'un {@link CompteLicence}), distinct de
 * l'enrolement {@code features.sync.SecondaryServer} (pre-cree par un admin).
 *
 * <p>Pas de relation JPA vers {@link CompteLicence} : {@code compteId} est un simple id, comme
 * {@code SecondaryServer.customerId} vers {@code Customer} — evite un couplage inutile et permet de
 * compter/filtrer par compte sans charger l'entite.
 *
 * <p>{@code (compteId, identifiantInstallationStable)} est la cle d'idempotence : un redemarrage ou
 * une reinstallation de la MEME machine physique retrouve son {@code LicenceServeur} existant au
 * lieu d'en recreer un (et donc de consommer un 2e siege du quota {@code nombreServeursAutorises}).
 */
@Entity
@Table(name = "licence_serveurs", uniqueConstraints =
        @UniqueConstraint(columnNames = {"compte_id", "identifiant_installation_stable"}))
@Getter
@Setter
public class LicenceServeur {

    public enum LicenceServeurStatut {
        ACTIF,
        REVOQUE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long compteId;

    @Enumerated(EnumType.STRING)
    private ServerRole role;

    /** UUID genere par l'installation elle-meme, stable a travers redemarrages/reinstallations. */
    private String identifiantInstallationStable;

    /** Adresse reseau auto-declaree par l'installation a chaque {@code /api/licence/enregistrer} ;
     * editable manuellement depuis l'ecran admin si la detection automatique se trompe. */
    private String adresseDeclaree;

    /** Id du {@code Customer} que represente ce serveur : rempli seulement pour le role {@code CLIENT}
     * (tache ulterieure du plan). {@code null} pour un {@code PARTNER}. */
    private Long customerId;

    @Enumerated(EnumType.STRING)
    private LicenceServeurStatut statut = LicenceServeurStatut.ACTIF;

    /** Code d'enrolement a usage unique en clair, regenere a chaque {@code /enregistrer} reussi
     * (y compris sur une reutilisation idempotente) : contrairement a {@code SyncApiKeys}'s
     * {@code enrollmentCode}, on ne le met jamais a {@code null} apres usage — {@code
     * codeEnrolementUtilise} porte seul cette information, pour que le code reste identifiable
     * (et son usage rejouable en 401) meme apres consommation. */
    private String codeEnrolement;

    private Instant codeEnrolementExpireA;

    private boolean codeEnrolementUtilise;

    /** Empreinte de la cle API, remplie apres l'echange via {@code /api/licence/cle}. La cle en clair
     * n'est jamais stockee. */
    private String cleApiHash;

    /** {@code true} une fois que le {@code PARTNER} de ce compte a vu cette ligne via {@code GET
     * /api/licence/nouveaux-serveurs} ET pousse son code local via {@code POST
     * /api/licence/code-partenaire} (Task 6). Remis a {@code false} par {@code
     * CompteLicenceAdminController#revokeLicenceServeur} quand {@code statut} passe a {@code
     * REVOQUE} : UNE SEULE colonne porte donc a la fois "jamais encore notifie" (nouvelle
     * installation) et "changement d'etat pas encore propage" (revocation) — {@code
     * LicenceServeurRepository#findByCompteIdAndRoleAndNotifiePartenaireFalse} n'a besoin d'aucune
     * 2e condition sur {@code statut}. Pertinent seulement pour le role {@code CLIENT} (un
     * {@code PARTNER} n'est jamais "decouvert" par lui-meme) ; toujours {@code false} pour un
     * {@code PARTNER}, sans consequence (jamais lu pour ce role). */
    private boolean notifiePartenaire;

    /** Code d'enrolement LOCAL (cote {@code features.sync}, PAS {@code codeEnrolement} ci-dessus qui
     * est propre au module {@code licence}) genere par le {@code PARTNER} pour le {@code
     * SecondaryServer} qu'il a materialise pour ce client, et pousse via {@code POST
     * /api/licence/code-partenaire} (Task 6) : c'est ce code que le {@code CLIENT} utilisera pour
     * terminer SA PROPRE inscription auprès du partenaire via le {@code POST /api/sync/enroll} deja
     * existant. {@code null} tant que le partenaire ne l'a pas encore pousse. */
    private String codeLocalPartenaire;

    /** Id du dernier {@code OrdreDeploiement} (module {@code features.deploiement}) deja servi a ce
     * serveur via {@code GET /api/licence/ordre-deploiement} ; {@code null} si aucun ordre n'a encore
     * ete consomme. Marque "consomme" le PLUS RECENT {@code OrdreDeploiement} seulement (pas un
     * historique complet) : {@code DeploiementController} ne sert jamais que le dernier ordre cree, le
     * plus simple suffisant pour ce besoin (voir le rapport de Task 3). */
    private Long dernierOrdreDeploiementConsommeId;

    private Instant creeA;

    @PrePersist
    protected void onCreate() {
        if (statut == null) {
            statut = LicenceServeurStatut.ACTIF;
        }
        if (creeA == null) {
            creeA = Instant.now();
        }
    }
}
