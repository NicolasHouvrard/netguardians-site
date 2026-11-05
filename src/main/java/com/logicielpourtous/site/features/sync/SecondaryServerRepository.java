package com.logicielpourtous.site.features.sync;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import com.logicielpourtous.site.features.commun.ServerRole;

import jakarta.persistence.LockModeType;

public interface SecondaryServerRepository extends JpaRepository<SecondaryServer, Long> {

    /**
     * Verrou pessimiste : deux enrolements concurrents avec le meme code ne peuvent pas obtenir
     * chacun une cle (le 2e attend le 1er, puis ne trouve plus le code, efface entre-temps).
     * A appeler dans une transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SecondaryServer> findByEnrollmentCode(String enrollmentCode);

    Optional<SecondaryServer> findByApiKeyHash(String apiKeyHash);

    /**
     * Serveurs d'un role et d'un statut donnes, rattaches a UN client (routage des entrees d'outbox
     * cote maitre : une demande ne part que vers les serveurs de son propre client).
     */
    List<SecondaryServer> findByRoleAndStatutAndCustomerId(ServerRole role, SecondaryServer.SecondaryServerStatut statut,
                                                           Long customerId);
}
