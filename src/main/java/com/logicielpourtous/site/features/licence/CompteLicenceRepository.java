package com.logicielpourtous.site.features.licence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface CompteLicenceRepository extends JpaRepository<CompteLicence, Long> {

    Optional<CompteLicence> findByLogin(String login);

    /**
     * Verrou pessimiste sur la ligne du compte : serialise les enrolements concurrents sous le
     * MEME compte (quota, unicite du PARTNER actif) — deux requetes {@code /api/licence/enregistrer}
     * concurrentes pour le meme {@code CompteLicence} ne peuvent plus toutes les deux lire le quota
     * avant que l'une d'elles n'ait sauvegarde son nouveau {@code LicenceServeur} : la 2e attend que
     * la 1ere committe, puis relit un compte a jour. A appeler dans une transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CompteLicence c where c.id = :id")
    Optional<CompteLicence> findByIdForUpdate(@Param("id") Long id);
}
