package com.logicielpourtous.site.features.intra;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IntraDashboardRepository extends JpaRepository<IntraDashboard, Long> {
    List<IntraDashboard> findByActiveTrueOrderByDisplayOrderAsc();
    Optional<IntraDashboard> findBySlugAndActiveTrue(String slug);
    Optional<IntraDashboard> findBySlug(String slug);
    boolean existsBySlug(String slug);
}
