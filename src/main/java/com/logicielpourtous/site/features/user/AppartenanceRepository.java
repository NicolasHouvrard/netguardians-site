package com.logicielpourtous.site.features.user;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AppartenanceRepository extends JpaRepository<Appartenance, Long> {

    List<Appartenance> findByUserIdOrderById(Long userId);

    /** Appartenances d'une personne a une societe du registre (par {@code societe_id}, jamais par tenant). */
    List<Appartenance> findByUserIdAndSocieteId(Long userId, Long societeId);

    /** Toutes les appartenances d'une societe du registre (par {@code societe_id}). */
    List<Appartenance> findBySocieteIdOrderById(Long societeId);
}
