package com.logicielpourtous.site.features.intervention;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface InterventionApplicationRepository extends JpaRepository<InterventionApplication, Long> {
    List<InterventionApplication> findByInterventionIdOrderByAppliedAtAsc(Long interventionId);
    List<InterventionApplication> findByInterventionIdAndStatus(Long interventionId, InterventionApplication.ApplicationStatus status);
}
