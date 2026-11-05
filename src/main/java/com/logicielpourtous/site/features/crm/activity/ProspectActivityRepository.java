package com.logicielpourtous.site.features.crm.activity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProspectActivityRepository extends JpaRepository<ProspectActivity, Long> {
    List<ProspectActivity> findByCustomerIdOrderByActivityDateDesc(Long customerId);

    List<ProspectActivity> findByOpportunityIdOrderByActivityDateDesc(Long opportunityId);
}
