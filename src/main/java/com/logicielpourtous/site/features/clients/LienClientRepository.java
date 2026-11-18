package com.logicielpourtous.site.features.clients;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LienClientRepository extends JpaRepository<LienClient, Long> {

    Optional<LienClient> findByClientSocieteId(Long clientSocieteId);

    List<LienClient> findByPartenaireSocieteId(Long partenaireSocieteId);
}
