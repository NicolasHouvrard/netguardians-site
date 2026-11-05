package com.logicielpourtous.site.features.accounting;

import com.logicielpourtous.site.features.commun.Tenant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class FecImportService {

    private static final DateTimeFormatter FEC_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final AccountingEntryRepository entryRepo;
    private final AccountingJournalRepository journalRepo;
    private final AccountingAccountRepository accountRepo;
    private final FiscalYearRepository fiscalYearRepo;
    private final AccountingService accountingService;

    public FecImportService(AccountingEntryRepository entryRepo,
                             AccountingJournalRepository journalRepo,
                             AccountingAccountRepository accountRepo,
                             FiscalYearRepository fiscalYearRepo,
                             AccountingService accountingService) {
        this.entryRepo = entryRepo;
        this.journalRepo = journalRepo;
        this.accountRepo = accountRepo;
        this.fiscalYearRepo = fiscalYearRepo;
        this.accountingService = accountingService;
    }

    @Transactional
    public FecImportResult importFec(Tenant tenant, InputStream inputStream, String filename) throws IOException {
        byte[] bytes = inputStream.readAllBytes();
        String content;
        try {
            content = new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            content = new String(bytes, Charset.forName("ISO-8859-1"));
        }

        List<String> lines = Arrays.stream(content.split("\\r?\\n"))
                .filter(l -> !l.isBlank())
                .toList();

        if (lines.isEmpty()) return new FecImportResult(0, 0, 0, List.of("Fichier vide"));

        int startLine = 0;
        if (lines.get(0).startsWith("JournalCode")) startLine = 1;

        Map<String, List<FecLine>> grouped = new LinkedHashMap<>();
        List<String> parseErrors = new ArrayList<>();

        for (int i = startLine; i < lines.size(); i++) {
            try {
                FecLine fl = parseLine(lines.get(i));
                grouped.computeIfAbsent(fl.ecritureNum(), k -> new ArrayList<>()).add(fl);
            } catch (Exception e) {
                parseErrors.add("Ligne " + (i + 1) + " : " + e.getMessage());
            }
        }

        int created = 0, skipped = 0, errors = 0;

        for (Map.Entry<String, List<FecLine>> entry : grouped.entrySet()) {
            String ecritureNum = entry.getKey();
            List<FecLine> fecLines = entry.getValue();

            if (entryRepo.existsByReferenceAndTenant(ecritureNum, tenant)) {
                skipped++;
                continue;
            }

            try {
                FecLine first = fecLines.get(0);
                LocalDate date = LocalDate.parse(first.ecritureDate(), FEC_DATE);

                AccountingJournal journal = findOrCreateJournal(tenant, first.journalCode(), first.journalLib());
                FiscalYear year = resolveFiscalYear(tenant, date);

                AccountingEntry accountingEntry = new AccountingEntry();
                accountingEntry.setTenant(tenant);
                accountingEntry.setJournal(journal);
                accountingEntry.setFiscalYear(year);
                accountingEntry.setEntryDate(date);
                accountingEntry.setReference(ecritureNum);
                accountingEntry.setDescription(first.ecritureLib());

                for (FecLine fl : fecLines) {
                    AccountingAccount account = findOrCreateAccount(tenant, fl.compteNum(), fl.compteLib());
                    AccountingEntryLine l = new AccountingEntryLine();
                    l.setAccount(account);
                    l.setLabel(fl.ecritureLib().isBlank() ? first.ecritureLib() : fl.ecritureLib());
                    l.setDebit(parseFrenchDecimal(fl.debit()));
                    l.setCredit(parseFrenchDecimal(fl.credit()));
                    if (!fl.ecritureLet().isBlank()) l.setLettrage(fl.ecritureLet());
                    l.setEntry(accountingEntry);
                    accountingEntry.getLines().add(l);
                }

                entryRepo.save(accountingEntry);
                created++;
            } catch (Exception e) {
                parseErrors.add("Écriture " + ecritureNum + " : " + e.getMessage());
                errors++;
            }
        }

        return new FecImportResult(created, skipped, errors, parseErrors);
    }

    private AccountingJournal findOrCreateJournal(Tenant tenant, String code, String lib) {
        return journalRepo.findByCodeAndTenant(code, tenant).orElseGet(() -> {
            AccountingJournal j = new AccountingJournal();
            j.setTenant(tenant);
            j.setCode(code);
            j.setLabel(lib != null && !lib.isBlank() ? lib : code);
            j.setType(JournalType.PAYROLL);
            return journalRepo.save(j);
        });
    }

    private AccountingAccount findOrCreateAccount(Tenant tenant, String number, String lib) {
        return accountRepo.findByNumberAndTenant(number, tenant).orElseGet(() -> {
            AccountingAccount a = new AccountingAccount();
            a.setTenant(tenant);
            a.setNumber(number);
            a.setLabel(lib != null && !lib.isBlank() ? lib : number);
            return accountRepo.save(a);
        });
    }

    private FiscalYear resolveFiscalYear(Tenant tenant, LocalDate date) {
        return fiscalYearRepo.findByTenantOrderByStartDateDesc(tenant).stream()
                .filter(y -> !date.isBefore(y.getStartDate()) && !date.isAfter(y.getEndDate()))
                .findFirst()
                .orElseGet(() -> accountingService.getCurrentFiscalYear(tenant));
    }

    private FecLine parseLine(String line) {
        String[] p = line.split("\\|", -1);
        return new FecLine(
                get(p, 0), get(p, 1), get(p, 2), get(p, 3),
                get(p, 4), get(p, 5), get(p, 6), get(p, 7),
                get(p, 8), get(p, 9), get(p, 10),
                get(p, 11), get(p, 12),
                get(p, 13), get(p, 14)
        );
    }

    private String get(String[] arr, int idx) {
        return (idx < arr.length) ? arr[idx].trim() : "";
    }

    private BigDecimal parseFrenchDecimal(String s) {
        if (s == null || s.isBlank() || s.equals("0") || s.equals("0,00")) return BigDecimal.ZERO;
        return new BigDecimal(s.replace(" ", "").replace(",", "."));
    }

    record FecLine(String journalCode, String journalLib, String ecritureNum, String ecritureDate,
                   String compteNum, String compteLib, String compAuxNum, String compAuxLib,
                   String pieceRef, String pieceDate, String ecritureLib,
                   String debit, String credit, String ecritureLet, String dateLet) {}

    public record FecImportResult(int created, int skipped, int errors, List<String> errorDetails) {}
}
