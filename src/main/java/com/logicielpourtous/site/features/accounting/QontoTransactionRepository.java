package com.logicielpourtous.site.features.accounting;

import com.logicielpourtous.site.features.commun.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface QontoTransactionRepository extends JpaRepository<QontoTransaction, Long> {
    boolean existsByQontoId(String qontoId);
    Optional<QontoTransaction> findByQontoId(String qontoId);
    Optional<QontoTransaction> findByIdAndTenant(Long id, Tenant tenant);
    List<QontoTransaction> findTop200ByTenantOrderByImportedAtDesc(Tenant tenant);
    List<QontoTransaction> findTop200ByTenantAndReconciledFalseAndImportStatusOrderByImportedAtDesc(Tenant tenant, String importStatus);
    List<QontoTransaction> findTop200ByTenantAndReconciledFalseOrderByImportedAtDesc(Tenant tenant);
    List<QontoTransaction> findTop200ByTenantAndReconciledTrueOrderByImportedAtDesc(Tenant tenant);
    long countByTenantAndImportStatus(Tenant tenant, String status);
    long countByTenantAndReconciledFalseAndImportStatus(Tenant tenant, String importStatus);
    long countByTenantAndReconciledFalse(Tenant tenant);
    @Query("SELECT t FROM QontoTransaction t WHERE t.tenant = :tenant AND t.accountingEntryId IS NOT NULL AND t.bankAccountNumber IS NULL")
    List<QontoTransaction> findNeedingBankAccountBackfill(@Param("tenant") Tenant tenant);
    // Per bank account
    List<QontoTransaction> findTop200ByTenantAndBankAccountNumberOrderByImportedAtDesc(Tenant tenant, String bankAccountNumber);
    List<QontoTransaction> findTop200ByTenantAndBankAccountNumberAndReconciledFalseAndImportStatusOrderByImportedAtDesc(Tenant tenant, String bankAccountNumber, String importStatus);
    List<QontoTransaction> findTop200ByTenantAndBankAccountNumberAndReconciledTrueOrderByImportedAtDesc(Tenant tenant, String bankAccountNumber);
    long countByTenantAndBankAccountNumberAndImportStatus(Tenant tenant, String bankAccountNumber, String status);
    long countByTenantAndBankAccountNumberAndReconciledFalseAndImportStatus(Tenant tenant, String bankAccountNumber, String importStatus);
    List<QontoTransaction> findByAccountingEntryIdIn(List<Long> entryIds);
    List<QontoTransaction> findByTenantAndReconciledFalseAndSideOrderByImportedAtDesc(Tenant tenant, String side);
}
