package com.logicielpourtous.site.features.clients;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReglageManagerRepository extends JpaRepository<ReglageManager, ReglageManager.Cle> {

    List<ReglageManager> findByClientSocieteIdAndRoleNom(Long clientSocieteId, String roleNom);

    boolean existsByClientSocieteIdAndDroitAndRoleNomIn(Long clientSocieteId, String droit, Collection<String> rolesNoms);

    void deleteByClientSocieteIdAndRoleNom(Long clientSocieteId, String roleNom);
}
