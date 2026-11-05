package com.logicielpourtous.site.features.subscription;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface SubscriptionPlanRepository extends JpaRepository<SubscriptionPlan, Long> {

    @Query("SELECT p FROM SubscriptionPlan p LEFT JOIN p.category c WHERE p.active = true ORDER BY c.sortOrder ASC, c.label ASC, p.sortOrder ASC, p.name ASC")
    List<SubscriptionPlan> findByActiveTrueOrdered();

    @Query("SELECT p FROM SubscriptionPlan p LEFT JOIN p.category c ORDER BY c.sortOrder ASC, c.label ASC, p.sortOrder ASC, p.name ASC")
    List<SubscriptionPlan> findAllOrdered();
}
