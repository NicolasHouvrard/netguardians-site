package com.logicielpourtous.site.features.deploiement;

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
 * Un ordre de deploiement bleu-vert emis par un operateur (Task 5+ : l'Agent de deploiement) —
 * {@code version} est le tag Git/image a deployer (ex. {@code "v1.2.3"}).
 *
 * <p>Un {@link com.logicielpourtous.site.features.licence.LicenceServeur} enrole recupere le
 * dernier {@code OrdreDeploiement} pas encore consomme par lui via {@code GET
 * /api/licence/ordre-deploiement} ({@code DeploiementController}) : seul le PLUS RECENT ordre
 * compte (pas d'historique/file d'attente) — {@link
 * com.logicielpourtous.site.features.licence.LicenceServeur#getDernierOrdreDeploiementConsommeId()}
 * porte, par serveur, l'id du dernier ordre deja servi.
 */
@Entity
@Table(name = "ordres_deploiement")
@Getter
@Setter
public class OrdreDeploiement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Tag Git / image a deployer, ex. {@code "v1.2.3"}. */
    private String version;

    private Instant creeA;

    @PrePersist
    protected void onCreate() {
        if (creeA == null) {
            creeA = Instant.now();
        }
    }
}
