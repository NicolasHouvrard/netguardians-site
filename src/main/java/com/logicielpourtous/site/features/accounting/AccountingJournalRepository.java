package com.logicielpourtous.site.features.accounting;

import com.logicielpourtous.site.features.commun.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface AccountingJournalRepository extends JpaRepository<AccountingJournal, Long> {
    List<AccountingJournal> findByTenantAndActiveTrueOrderByCodeAsc(Tenant tenant);
    List<AccountingJournal> findByTenantOrderByCodeAsc(Tenant tenant);
    boolean existsByCodeAndTenant(String code, Tenant tenant);
    Optional<AccountingJournal> findByCodeAndTenant(String code, Tenant tenant);
}
