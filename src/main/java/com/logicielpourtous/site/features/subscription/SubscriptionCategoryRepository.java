package com.logicielpourtous.site.features.subscription;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SubscriptionCategoryRepository extends JpaRepository<SubscriptionCategory, Long> {
    List<SubscriptionCategory> findAllByOrderBySortOrderAscLabelAsc();
    List<SubscriptionCategory> findByActiveTrueOrderBySortOrderAscLabelAsc();
    boolean existsByLabel(String label);
}
