package com.logicielpourtous.site.features.auth;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DemandeSocieteConnueRepository extends JpaRepository<DemandeSocieteConnue, Long> {

    /** Une demande non traitee existe deja pour cette societe et cet e-mail (casse ignoree). */
    boolean existsBySocieteIdentiteIdAndEmailDemandeurIgnoreCaseAndTraiteeFalse(Long societeIdentiteId,
                                                                               String emailDemandeur);
}
