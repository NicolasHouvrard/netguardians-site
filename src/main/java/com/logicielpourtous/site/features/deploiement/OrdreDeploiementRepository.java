package com.logicielpourtous.site.features.deploiement;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrdreDeploiementRepository extends JpaRepository<OrdreDeploiement, Long> {

    /** Le plus recent {@link OrdreDeploiement} cree, s'il en existe un — c'est le seul qui compte
     * pour {@code DeploiementController} (pas d'historique/file d'attente, voir sa javadoc).
     * Departage par {@code id} (en plus de {@code creeA}) pour un ordre total deterministe meme
     * quand deux ordres sont crees dans le meme instant (resolution de l'horloge). */
    Optional<OrdreDeploiement> findTopByOrderByCreeADescIdDesc();
}
