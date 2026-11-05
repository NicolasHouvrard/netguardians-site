package com.logicielpourtous.site.features.accounting;

import com.logicielpourtous.site.features.commun.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface AccountingBatchRepository extends JpaRepository<AccountingBatch, Long> {
    List<AccountingBatch> findByTenantOrderByCreatedAtDesc(Tenant tenant);
    Optional<AccountingBatch> findByIdAndTenant(Long id, Tenant tenant);
    long countByTenantAndStatus(Tenant tenant, String status);
}
