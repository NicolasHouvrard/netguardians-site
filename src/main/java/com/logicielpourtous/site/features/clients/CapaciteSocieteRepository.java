package com.logicielpourtous.site.features.clients;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CapaciteSocieteRepository extends JpaRepository<CapaciteSociete, Long> {

    List<CapaciteSociete> findBySocieteId(Long societeId);

    List<CapaciteSociete> findBySocieteIdIn(java.util.Collection<Long> societeIds);

    boolean existsBySocieteIdAndCapacite(Long societeId, TypeCapacite capacite);
}
