package com.logicielpourtous.site.features.accounting;

import com.logicielpourtous.site.features.commun.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface StripeAccountingMappingRepository extends JpaRepository<StripeAccountingMapping, Long> {
    Optional<StripeAccountingMapping> findByEventTypeAndTenantAndActiveTrue(String eventType, Tenant tenant);
    Optional<StripeAccountingMapping> findByEventTypeAndTenant(String eventType, Tenant tenant);
    List<StripeAccountingMapping> findByTenantOrderByEventTypeAsc(Tenant tenant);
}
