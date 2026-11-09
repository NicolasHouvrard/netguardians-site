package com.logicielpourtous.site.features.auth;

import java.util.List;
import java.util.Optional;

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

    /** Idem pour le Maitre : societes sans proprietaire, et aussi les siennes si son id de tenant est proprietaire. */
    @Query("select d from DemandeSocieteConnue d join SocieteIdentite s on s.id = d.societeIdentiteId "
            + "where d.traitee = false and (s.proprietaireTenantId is null or s.proprietaireTenantId = :tenantId) "
            + "order by d.creeA desc, d.id desc")
    List<DemandeSocieteConnue> nonTraiteesDuMaitre(@Param("tenantId") Long tenantId);

    @Query("select count(d) from DemandeSocieteConnue d join SocieteIdentite s on s.id = d.societeIdentiteId "
            + "where d.traitee = false and s.proprietaireTenantId = :tenantId")
    int compterDuPartenaire(@Param("tenantId") Long tenantId);

    @Query("select count(d) from DemandeSocieteConnue d join SocieteIdentite s on s.id = d.societeIdentiteId "
            + "where d.traitee = false and (s.proprietaireTenantId is null or s.proprietaireTenantId = :tenantId)")
    int compterDuMaitre(@Param("tenantId") Long tenantId);

    /** Une demande non traitee, seulement si elle est dans le perimetre du partenaire. */
    @Query("select d from DemandeSocieteConnue d join SocieteIdentite s on s.id = d.societeIdentiteId "
            + "where d.id = :id and d.traitee = false and s.proprietaireTenantId = :tenantId")
    Optional<DemandeSocieteConnue> unDuPartenaire(@Param("id") Long id, @Param("tenantId") Long tenantId);

    @Query("select d from DemandeSocieteConnue d join SocieteIdentite s on s.id = d.societeIdentiteId "
            + "where d.id = :id and d.traitee = false "
            + "and (s.proprietaireTenantId is null or s.proprietaireTenantId = :tenantId)")
    Optional<DemandeSocieteConnue> unDuMaitre(@Param("id") Long id, @Param("tenantId") Long tenantId);
}
