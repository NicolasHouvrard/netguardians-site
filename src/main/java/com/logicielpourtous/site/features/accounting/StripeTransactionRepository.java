package com.logicielpourtous.site.features.accounting;

import com.logicielpourtous.site.features.commun.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface StripeTransactionRepository extends JpaRepository<StripeTransaction, Long> {
    boolean existsByStripeId(String stripeId);
    Optional<StripeTransaction> findByIdAndTenant(Long id, Tenant tenant);
    List<StripeTransaction> findTop200ByTenantOrderByImportedAtDesc(Tenant tenant);
    List<StripeTransaction> findTop200ByTenantAndReconciledFalseAndStripeStatusOrderByImportedAtDesc(Tenant tenant, String stripeStatus);
    List<StripeTransaction> findTop200ByTenantAndReconciledFalseOrderByImportedAtDesc(Tenant tenant);
    List<StripeTransaction> findTop200ByTenantAndReconciledTrueOrderByImportedAtDesc(Tenant tenant);
    long countByTenantAndStripeStatus(Tenant tenant, String status);
    long countByTenantAndReconciledFalseAndStripeStatus(Tenant tenant, String stripeStatus);
    long countByTenantAndReconciledFalse(Tenant tenant);
    @Query("SELECT t FROM StripeTransaction t WHERE t.tenant = :tenant AND t.accountingEntryId IS NOT NULL AND t.bankAccountNumber IS NULL")
    List<StripeTransaction> findNeedingBankAccountBackfill(@Param("tenant") Tenant tenant);
    // Per bank account
    List<StripeTransaction> findTop200ByTenantAndBankAccountNumberOrderByImportedAtDesc(Tenant tenant, String bankAccountNumber);
    List<StripeTransaction> findTop200ByTenantAndBankAccountNumberAndReconciledFalseAndStripeStatusOrderByImportedAtDesc(Tenant tenant, String bankAccountNumber, String stripeStatus);
    List<StripeTransaction> findTop200ByTenantAndBankAccountNumberAndReconciledTrueOrderByImportedAtDesc(Tenant tenant, String bankAccountNumber);
    long countByTenantAndBankAccountNumberAndStripeStatus(Tenant tenant, String bankAccountNumber, String status);
    long countByTenantAndBankAccountNumberAndReconciledFalseAndStripeStatus(Tenant tenant, String bankAccountNumber, String stripeStatus);
    List<StripeTransaction> findByAccountingEntryIdIn(List<Long> entryIds);
}
