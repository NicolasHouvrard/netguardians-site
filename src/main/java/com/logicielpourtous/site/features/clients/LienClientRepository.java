package com.logicielpourtous.site.features.clients;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;

public interface LienClientRepository extends JpaRepository<LienClient, Long> {

    Optional<LienClient> findByClientSocieteId(Long clientSocieteId);

    /** Lecture avec verrou d'ecriture : serialise les changements concurrents d'un meme client. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<LienClient> findWithLockByClientSocieteId(Long clientSocieteId);

    List<LienClient> findByPartenaireSocieteId(Long partenaireSocieteId);
}
