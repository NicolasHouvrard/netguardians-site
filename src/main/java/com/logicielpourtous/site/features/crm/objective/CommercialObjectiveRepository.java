package com.logicielpourtous.site.features.crm.objective;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CommercialObjectiveRepository extends JpaRepository<CommercialObjective, Long> {
    List<CommercialObjective> findAllByOrderByYearDescMonthDesc();
}
