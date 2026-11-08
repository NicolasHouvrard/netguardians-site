package com.logicielpourtous.site.features.identite;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SocieteIdentiteRepository extends JpaRepository<SocieteIdentite, Long> {

    Optional<SocieteIdentite> findBySiren(String siren);

    Optional<SocieteIdentite> findByTva(String tva);
}
