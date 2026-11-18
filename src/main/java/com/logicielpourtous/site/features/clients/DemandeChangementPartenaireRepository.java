package com.logicielpourtous.site.features.clients;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;

public interface DemandeChangementPartenaireRepository extends JpaRepository<DemandeChangementPartenaire, Long> {

    /** Lecture avec verrou d'ecriture : deux decisions concurrentes sur la meme demande se serialisent. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<DemandeChangementPartenaire> findWithLockById(Long id);

    boolean existsByClientSocieteIdAndStatut(Long clientSocieteId, String statut);
}
