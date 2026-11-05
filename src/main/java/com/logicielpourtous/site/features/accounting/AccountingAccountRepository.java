package com.logicielpourtous.site.features.accounting;

import com.logicielpourtous.site.features.commun.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface AccountingAccountRepository extends JpaRepository<AccountingAccount, Long> {
    List<AccountingAccount> findByTenantAndActiveTrueOrderByNumberAsc(Tenant tenant);
    List<AccountingAccount> findByTenantOrderByNumberAsc(Tenant tenant);
    boolean existsByNumberAndTenant(String number, Tenant tenant);
    Optional<AccountingAccount> findByNumberAndTenant(String number, Tenant tenant);
}
