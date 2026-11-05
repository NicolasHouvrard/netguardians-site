package com.logicielpourtous.site.features.accounting;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class FecExportService {

    private static final DateTimeFormatter FEC_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String HEADER =
            "JournalCode|JournalLib|EcritureNum|EcritureDate|CompteNum|CompteLib|CompAuxNum|CompAuxLib|" +
            "PieceRef|PieceDate|EcritureLib|Debit|Credit|EcritureLet|DateLet|ValidDate|Montantdevise|Idevise";

    private final AccountingEntryRepository entryRepo;

    public FecExportService(AccountingEntryRepository entryRepo) {
        this.entryRepo = entryRepo;
    }

    public byte[] exportFec(FiscalYear year) {
        List<AccountingEntry> entries = entryRepo.findByTenantAndFiscalYearOrderByEntryDateAscIdAsc(year.getTenant(), year);

        StringBuilder sb = new StringBuilder();
        sb.append(HEADER).append("\r\n");

        for (AccountingEntry entry : entries) {
            String jCode  = entry.getJournal().getCode();
            String jLib   = escape(entry.getJournal().getLabel());
            String eNum   = entry.getReference();
            String eDate  = entry.getEntryDate().format(FEC_DATE);
            String pieceDate = eDate;
            String eLib   = escape(entry.getDescription() != null ? entry.getDescription() : "");

            for (AccountingEntryLine line : entry.getLines()) {
                String compNum = line.getAccount().getNumber();
                String compLib = escape(line.getAccount().getLabel());
                String lineLib = escape(line.getLabel() != null ? line.getLabel() : eLib);
                String debit   = formatAmount(line.getDebit());
                String credit  = formatAmount(line.getCredit());
                String lettrage = line.getLettrage() != null ? line.getLettrage() : "";

                sb.append(jCode).append("|")
                  .append(jLib).append("|")
                  .append(eNum).append("|")
                  .append(eDate).append("|")
                  .append(compNum).append("|")
                  .append(compLib).append("|")
                  .append("|") // CompAuxNum
                  .append("|") // CompAuxLib
                  .append(eNum).append("|") // PieceRef
                  .append(pieceDate).append("|") // PieceDate
                  .append(lineLib).append("|")
                  .append(debit).append("|")
                  .append(credit).append("|")
                  .append(lettrage).append("|")
                  .append("|") // DateLet
                  .append("|") // ValidDate
                  .append("|") // Montantdevise
                  .append("\r\n");
            }
        }

        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String formatAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) return "0,00";
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString().replace(".", ",");
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("|", " ").replace("\r", " ").replace("\n", " ");
    }
}
