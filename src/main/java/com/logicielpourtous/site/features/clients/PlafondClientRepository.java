package com.logicielpourtous.site.features.clients;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PlafondClientRepository extends JpaRepository<PlafondClient, PlafondClient.Cle> {

    List<PlafondClient> findByClientSocieteId(Long clientSocieteId);

    boolean existsByClientSocieteIdAndDroit(Long clientSocieteId, String droit);

    void deleteByClientSocieteId(Long clientSocieteId);
}
