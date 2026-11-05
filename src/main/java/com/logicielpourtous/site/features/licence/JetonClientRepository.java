package com.logicielpourtous.site.features.licence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;

public interface JetonClientRepository extends JpaRepository<JetonClient, Long> {

    /**
     * Verrou pessimiste : deux consommations concurrentes du meme jeton (meme code) ne peuvent pas
     * toutes les deux reussir — la 2e attend que la 1ere committe, puis trouve {@code utilise=true}.
     * Meme patron que {@code LicenceServeurRepository#findByCodeEnrolement}. A appeler dans une
     * transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<JetonClient> findByCode(String code);
}
