package com.logicielpourtous.site.features.licence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import com.logicielpourtous.site.features.commun.ServerRole;

import jakarta.persistence.LockModeType;

public interface LicenceServeurRepository extends JpaRepository<LicenceServeur, Long> {

    /** Cle d'idempotence : retrouve l'installation existante pour ne pas consommer un 2e siege. */
    Optional<LicenceServeur> findByCompteIdAndIdentifiantInstallationStable(Long compteId,
                                                                            String identifiantInstallationStable);

    List<LicenceServeur> findByCompteId(Long compteId);

    /** Tous les {@link LicenceServeur} (tout compte confondu) a ce statut — utilise par {@code
     * DeploiementScheduler} (Task 4) pour reveiller l'Agent de chaque installation {@code ACTIF}. */
    List<LicenceServeur> findByStatut(LicenceServeur.LicenceServeurStatut statut);

    /**
     * Les {@code LicenceServeur} {@code CLIENT} du compte PAS ENCORE notifies au {@code PARTNER}
     * (Task 6, {@code GET /api/licence/nouveaux-serveurs}) : nouvelles installations jamais vues, ET
     * revocations pas encore propagees (meme colonne, voir {@link LicenceServeur#isNotifiePartenaire()}).
     */
    List<LicenceServeur> findByCompteIdAndRoleAndNotifiePartenaireFalse(Long compteId, ServerRole role);

    long countByCompteIdAndStatut(Long compteId, LicenceServeur.LicenceServeurStatut statut);

    boolean existsByCompteIdAndRoleAndStatut(Long compteId, ServerRole role, LicenceServeur.LicenceServeurStatut statut);

    /**
     * Verrou pessimiste : deux echanges concurrents avec le meme code ne peuvent pas obtenir
     * chacun une cle (le 2e attend le 1er, puis trouve {@code codeEnrolementUtilise=true}).
     * A appeler dans une transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<LicenceServeur> findByCodeEnrolement(String codeEnrolement);

    Optional<LicenceServeur> findByCleApiHash(String cleApiHash);
}
