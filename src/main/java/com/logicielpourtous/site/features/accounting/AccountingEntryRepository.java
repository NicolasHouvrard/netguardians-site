package com.logicielpourtous.site.features.accounting;

import com.logicielpourtous.site.features.commun.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AccountingEntryRepository extends JpaRepository<AccountingEntry, Long> {
    List<AccountingEntry> findByTenantAndFiscalYearOrderByEntryDateAscIdAsc(Tenant tenant, FiscalYear year);
    List<AccountingEntry> findByTenantAndJournalAndFiscalYearOrderByEntryDateAscIdAsc(Tenant tenant, AccountingJournal journal, FiscalYear year);
    long countByJournalAndFiscalYear(AccountingJournal journal, FiscalYear year);
    boolean existsByReferenceAndTenant(String reference, Tenant tenant);
    List<AccountingEntry> findByBatchIdOrderByIdAsc(Long batchId);
    long countByBatchId(Long batchId);
    List<AccountingEntry> findByTenantAndReferenceStartingWithOrderByEntryDateAsc(Tenant tenant, String prefix);
}
