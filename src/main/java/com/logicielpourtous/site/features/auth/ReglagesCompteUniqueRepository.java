package com.logicielpourtous.site.features.auth;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface ReglagesCompteUniqueRepository extends JpaRepository<ReglagesCompteUnique, Long> {

    /** Cree la ligne unique si elle n'existe pas ; idempotent, sans erreur si une autre transaction l'a creee. */
    @Modifying
    @Query(value = "INSERT INTO public.reglages_compte_unique (id, version, modifie_a) VALUES (1, 0, now()) "
            + "ON CONFLICT (id) DO NOTHING", nativeQuery = true)
    int creerSiAbsente();

    /** Lit la ligne unique en la verrouillant jusqu'a la fin de la transaction (ecritures serialisees). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReglagesCompteUnique r where r.id = 1")
    Optional<ReglagesCompteUnique> verrouiller();
}
