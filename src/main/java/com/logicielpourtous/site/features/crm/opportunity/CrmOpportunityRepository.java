package com.logicielpourtous.site.features.crm.opportunity;

import com.logicielpourtous.site.features.customers.ProspectStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CrmOpportunityRepository extends JpaRepository<CrmOpportunity, Long> {

    List<CrmOpportunity> findAllByOrderByCreatedAtDesc();

    List<CrmOpportunity> findByStatusOrderByExpectedCloseDateAsc(ProspectStatus status);

    @Query("SELECT o FROM CrmOpportunity o WHERE o.assignedCommercialId = :uid ORDER BY o.expectedCloseDate ASC")
    List<CrmOpportunity> findByCommercialId(@Param("uid") Long uid);

    @Query("""
            SELECT o FROM CrmOpportunity o
            WHERE LOWER(o.name) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(o.customer.companyName) LIKE LOWER(CONCAT('%', :q, '%'))
            ORDER BY o.createdAt DESC
            """)
    List<CrmOpportunity> search(@Param("q") String q);

    List<CrmOpportunity> findByCustomerIdOrderByCreatedAtDesc(Long customerId);
}
