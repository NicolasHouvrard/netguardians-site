package com.logicielpourtous.site.features.intra;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface IntraWidgetRepository extends JpaRepository<IntraWidget, Long> {
    List<IntraWidget> findByActiveTrueOrderByDisplayOrderAsc();
    Optional<IntraWidget> findByWidgetKey(String widgetKey);
    boolean existsByWidgetKey(String widgetKey);
    List<IntraWidget> findByModuleIdOrderByDisplayOrderAsc(Long moduleId);
}
