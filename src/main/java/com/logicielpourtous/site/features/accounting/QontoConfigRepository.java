package com.logicielpourtous.site.features.accounting;

import com.logicielpourtous.site.features.commun.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface QontoConfigRepository extends JpaRepository<QontoConfig, Long> {
    List<QontoConfig> findAllByTenantOrderByIdAsc(Tenant tenant);
    Optional<QontoConfig> findFirstByTenantOrderByIdAsc(Tenant tenant);
    List<QontoConfig> findBySyncEnabledTrueAndActiveTrueOrderByIdAsc();
}
