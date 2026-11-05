package com.logicielpourtous.site.features.accounting;

import com.logicielpourtous.site.features.commun.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface FiscalYearRepository extends JpaRepository<FiscalYear, Long> {
    List<FiscalYear> findByCurrentTrueAndTenantOrderByStartDateDesc(Tenant tenant);
    List<FiscalYear> findByTenantOrderByStartDateDesc(Tenant tenant);

    @Modifying
    @Query("UPDATE FiscalYear f SET f.current = false WHERE f.tenant = :tenant")
    void clearCurrentFlagsByTenant(@Param("tenant") Tenant tenant);
}
