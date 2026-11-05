package com.logicielpourtous.site.features.accounting;

import com.logicielpourtous.site.features.commun.Tenant;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AccountingService {

    private final AccountingEntryRepository entryRepo;
    private final AccountingEntryLineRepository lineRepo;
    private final FiscalYearRepository fiscalYearRepo;
    private final AccountingAccountRepository accountRepo;

    public AccountingService(AccountingEntryRepository entryRepo,
                             AccountingEntryLineRepository lineRepo,
                             FiscalYearRepository fiscalYearRepo,
                             AccountingAccountRepository accountRepo) {
        this.entryRepo = entryRepo;
        this.lineRepo = lineRepo;
        this.fiscalYearRepo = fiscalYearRepo;
        this.accountRepo = accountRepo;
    }

    public FiscalYear getCurrentFiscalYear(Tenant tenant) {
        return fiscalYearRepo.findByCurrentTrueAndTenantOrderByStartDateDesc(tenant)
                .stream().findFirst()
                .orElseGet(() -> fiscalYearRepo.findByTenantOrderByStartDateDesc(tenant).stream().findFirst().orElse(null));
    }

    public String generateReference(AccountingJournal journal, FiscalYear year) {
        long count = entryRepo.countByJournalAndFiscalYear(journal, year) + 1;
        String y = String.valueOf(year.getStartDate().getYear());
        return journal.getCode() + "-" + y + "-" + String.format("%05d", count);
    }

    public record BalanceLine(AccountingAccount account, BigDecimal totalDebit, BigDecimal totalCredit, BigDecimal solde) {}

    public List<BalanceLine> getBalance(FiscalYear year, Tenant tenant) {
        List<Object[]> raw = lineRepo.getBalanceByAccountAndTenant(year, tenant);
        Map<Long, Object[]> byId = new HashMap<>();
        for (Object[] r : raw) byId.put((Long) r[0], r);

        return accountRepo.findByTenantOrderByNumberAsc(tenant).stream()
                .filter(a -> byId.containsKey(a.getId()))
                .map(a -> {
                    Object[] r = byId.get(a.getId());
                    BigDecimal d = r[1] != null ? (BigDecimal) r[1] : BigDecimal.ZERO;
                    BigDecimal c = r[2] != null ? (BigDecimal) r[2] : BigDecimal.ZERO;
                    return new BalanceLine(a, d, c, d.subtract(c));
                })
                .collect(Collectors.toList());
    }
}
