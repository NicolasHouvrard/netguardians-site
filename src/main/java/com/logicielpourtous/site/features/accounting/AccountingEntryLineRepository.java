package com.logicielpourtous.site.features.accounting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.util.List;

public interface AccountingEntryLineRepository extends JpaRepository<AccountingEntryLine, Long> {

    @Query("SELECT l FROM AccountingEntryLine l WHERE l.account = :account AND l.entry.fiscalYear = :year ORDER BY l.entry.entryDate ASC, l.entry.id ASC, l.id ASC")
    List<AccountingEntryLine> findByAccountAndFiscalYear(@Param("account") AccountingAccount account, @Param("year") FiscalYear year);

    @Query("SELECT l.account.id, SUM(l.debit), SUM(l.credit) FROM AccountingEntryLine l WHERE l.entry.fiscalYear = :year AND l.entry.tenant = :tenant GROUP BY l.account.id")
    List<Object[]> getBalanceByAccountAndTenant(@Param("year") FiscalYear year, @Param("tenant") com.logicielpourtous.site.features.commun.Tenant tenant);

    @Query("SELECT l FROM AccountingEntryLine l " +
           "WHERE l.entry.fiscalYear = :year AND l.entry.tenant = :tenant " +
           "AND l.account.number LIKE CONCAT(:prefix, '%') " +
           "ORDER BY l.entry.partner ASC NULLS LAST, l.entry.entryDate ASC, l.entry.id ASC, l.id ASC")
    List<AccountingEntryLine> findForLivreDesTiers(@Param("year") FiscalYear year,
                                                   @Param("tenant") com.logicielpourtous.site.features.commun.Tenant tenant,
                                                   @Param("prefix") String prefix);

    @Query("SELECT COALESCE(SUM(l.debit), 0) - COALESCE(SUM(l.credit), 0) FROM AccountingEntryLine l WHERE l.account.number = :number AND l.entry.tenant = :tenant")
    BigDecimal getBalanceByAccountNumberAndTenant(@Param("number") String number,
                                                  @Param("tenant") com.logicielpourtous.site.features.commun.Tenant tenant);
}
