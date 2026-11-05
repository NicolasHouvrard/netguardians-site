package com.logicielpourtous.site.features.intervention;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface InterventionImageRepository extends JpaRepository<InterventionImage, Long> {
    List<InterventionImage> findByInterventionIdOrderByUploadedAtAsc(Long interventionId);
}
