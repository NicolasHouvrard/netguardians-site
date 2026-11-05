package com.logicielpourtous.site.features.shop;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PricingRuleRepository extends JpaRepository<PricingRule, Long> {

    List<PricingRule> findAllByOrderByNameAsc();

    Optional<PricingRule> findFirstByIsDefaultTrue();

    boolean existsByIsDefaultTrue();
}
