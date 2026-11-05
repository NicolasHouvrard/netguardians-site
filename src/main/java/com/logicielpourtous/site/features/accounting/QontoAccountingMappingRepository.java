package com.logicielpourtous.site.features.accounting;

import com.logicielpourtous.site.features.commun.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface QontoAccountingMappingRepository extends JpaRepository<QontoAccountingMapping, Long> {
    Optional<QontoAccountingMapping> findByMappingKeyAndTenantAndActiveTrue(String mappingKey, Tenant tenant);
    Optional<QontoAccountingMapping> findByMappingKeyAndTenant(String mappingKey, Tenant tenant);
    List<QontoAccountingMapping> findByTenantOrderByMappingKeyAsc(Tenant tenant);
}
