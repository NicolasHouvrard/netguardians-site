package com.logicielpourtous.site.features.auth;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DemandeSocieteConnueRepository extends JpaRepository<DemandeSocieteConnue, Long> {

    /** Une demande non traitee existe deja pour cette societe et cet e-mail (casse ignoree). */
    boolean existsBySocieteIdentiteIdAndEmailDemandeurIgnoreCaseAndTraiteeFalse(Long societeIdentiteId,
                                                                               String emailDemandeur);

    /** Demandes non traitees des societes appartenant au partenaire donne, les plus recentes d'abord. */
    @Query("select d from DemandeSocieteConnue d join SocieteIdentite s on s.id = d.societeIdentiteId "
            + "where d.traitee = false and s.proprietaireTenantId = :tenantId order by d.creeA desc, d.id desc")
    List<DemandeSocieteConnue> nonTraiteesDuPartenaire(@Param("tenantId") Long tenantId);

    /** Demandes non traitees des societes qui appartiennent au Maitre (proprietaire nul). */
    @Query("select d from DemandeSocieteConnue d join SocieteIdentite s on s.id = d.societeIdentiteId "
            + "where d.traitee = false and s.proprietaireTenantId is null order by d.creeA desc, d.id desc")
    List<DemandeSocieteConnue> nonTraiteesDuMaitre();
}
