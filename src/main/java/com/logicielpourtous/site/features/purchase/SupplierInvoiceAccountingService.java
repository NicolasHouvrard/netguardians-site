package com.logicielpourtous.site.features.purchase;

import com.logicielpourtous.site.features.accounting.*;
import com.logicielpourtous.site.features.commun.Tenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * VALIDATED (TO_PAY) → journal AC : D 6xx Charges + D 445660 TVA déductible / C 401000 Fournisseurs
 * PAID               → journal BQ : D 401000 Fournisseurs / C 512000 Banque
 * CANCELLED          → extourne du journal AC
 */
@Service
public class SupplierInvoiceAccountingService {

    private static final Logger log = LoggerFactory.getLogger(SupplierInvoiceAccountingService.class);

    private final AccountingAccountRepository accountRepo;
    private final AccountingJournalRepository journalRepo;
    private final AccountingEntryRepository   entryRepo;
    private final AccountingService           accountingService;

    public SupplierInvoiceAccountingService(AccountingAccountRepository accountRepo,
                                             AccountingJournalRepository journalRepo,
                                             AccountingEntryRepository entryRepo,
                                             AccountingService accountingService) {
        this.accountRepo      = accountRepo;
        this.journalRepo      = journalRepo;
        this.entryRepo        = entryRepo;
        this.accountingService = accountingService;
    }

    @Transactional
    public void onInvoiceValidated(Tenant tenant, SupplierInvoice invoice) {
        String ref = "FURN-" + safeRef(invoice) + "-AC";
        if (entryRepo.existsByReferenceAndTenant(ref, tenant)) return;

        AccountingJournal ac   = findJournal(tenant, "AC").orElseGet(() -> findJournal(tenant, "HA").orElse(null));
        FiscalYear year        = accountingService.getCurrentFiscalYear(tenant);
        if (ac == null || year == null) { log.warn("Journal AC/HA ou exercice manquant"); return; }

        var fournisseurs = findAccount(tenant, "401000").orElse(null);
        var chargeAcc    = invoice.getChargeAccountNumber() != null
                ? findAccount(tenant, invoice.getChargeAccountNumber()).orElse(null)
                : findAccount(tenant, "604000").orElse(null);
        var tvaDeduct    = findAccount(tenant, "445660").orElse(null);
        if (fournisseurs == null || chargeAcc == null) { log.warn("Comptes 401/6xx manquants"); return; }

        BigDecimal ht  = nvl(invoice.getTotalHT());
        BigDecimal tva = nvl(invoice.getTotalTVA());
        BigDecimal ttc = nvl(invoice.getTotalTTC());

        AccountingEntry entry = build(tenant, ac, year, ref,
                "Facture " + safeRef(invoice) + " — " + invoice.getVendorName(),
                nvlDate(invoice.getInvoiceDate()), invoice.getVendorName());

        addLine(entry, chargeAcc,    "Charges — " + invoice.getVendorName(), ht, BigDecimal.ZERO);
        if (tvaDeduct != null && tva.compareTo(BigDecimal.ZERO) > 0) {
            addLine(entry, tvaDeduct, "TVA déductible",                      tva, BigDecimal.ZERO);
        }
        addLine(entry, fournisseurs, "Fournisseur — " + invoice.getVendorName(), BigDecimal.ZERO, ttc);

        entryRepo.save(entry);
        invoice.setAccountingEntryId(entry.getId());
        log.info("Écriture AC créée {} pour facture fournisseur", ref);
    }

    @Transactional
    public void onInvoicePaid(Tenant tenant, SupplierInvoice invoice) {
        String ref = "FURN-" + safeRef(invoice) + "-BQ";
        if (entryRepo.existsByReferenceAndTenant(ref, tenant)) return;

        AccountingJournal bq = findJournal(tenant, "BQ").orElse(null);
        FiscalYear year      = accountingService.getCurrentFiscalYear(tenant);
        if (bq == null || year == null) return;

        var banque       = findAccount(tenant, "512000").orElse(null);
        var fournisseurs = findAccount(tenant, "401000").orElse(null);
        if (banque == null || fournisseurs == null) return;

        BigDecimal ttc = nvl(invoice.getTotalTTC());

        AccountingEntry entry = build(tenant, bq, year, ref,
                "Règlement " + safeRef(invoice) + " — " + invoice.getVendorName(),
                LocalDate.now(), invoice.getVendorName());

        addLine(entry, fournisseurs, "Fournisseur — " + invoice.getVendorName(), ttc, BigDecimal.ZERO);
        addLine(entry, banque,       "Banque",                                   BigDecimal.ZERO, ttc);

        entryRepo.save(entry);
        invoice.setPaymentEntryId(entry.getId());
        log.info("Écriture BQ fournisseur créée {}", ref);
    }

    @Transactional
    public void onInvoiceCancelled(Tenant tenant, SupplierInvoice invoice) {
        String ref = "FURN-" + safeRef(invoice) + "-AV";
        if (entryRepo.existsByReferenceAndTenant(ref, tenant)) return;

        AccountingJournal ac = findJournal(tenant, "AC").orElseGet(() -> findJournal(tenant, "HA").orElse(null));
        FiscalYear year      = accountingService.getCurrentFiscalYear(tenant);
        if (ac == null || year == null) return;

        var fournisseurs = findAccount(tenant, "401000").orElse(null);
        var chargeAcc    = invoice.getChargeAccountNumber() != null
                ? findAccount(tenant, invoice.getChargeAccountNumber()).orElse(null)
                : findAccount(tenant, "604000").orElse(null);
        var tvaDeduct    = findAccount(tenant, "445660").orElse(null);
        if (fournisseurs == null || chargeAcc == null) return;

        BigDecimal ht  = nvl(invoice.getTotalHT());
        BigDecimal tva = nvl(invoice.getTotalTVA());
        BigDecimal ttc = nvl(invoice.getTotalTTC());

        AccountingEntry entry = build(tenant, ac, year, ref,
                "Annulation " + safeRef(invoice) + " — " + invoice.getVendorName(),
                LocalDate.now(), invoice.getVendorName());

        addLine(entry, fournisseurs, "Extourne fournisseur", ttc, BigDecimal.ZERO);
        addLine(entry, chargeAcc,    "Extourne charge",      BigDecimal.ZERO, ht);
        if (tvaDeduct != null && tva.compareTo(BigDecimal.ZERO) > 0) {
            addLine(entry, tvaDeduct, "Extourne TVA déductible", BigDecimal.ZERO, tva);
        }

        entryRepo.save(entry);
        log.info("Extourne AC fournisseur créée {}", ref);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Optional<AccountingJournal> findJournal(Tenant tenant, String code) {
        return journalRepo.findByCodeAndTenant(code, tenant);
    }

    private Optional<AccountingAccount> findAccount(Tenant tenant, String number) {
        return accountRepo.findByNumberAndTenant(number, tenant);
    }

    private AccountingEntry build(Tenant tenant, AccountingJournal journal, FiscalYear year,
                                   String ref, String desc, LocalDate date, String partner) {
        AccountingEntry e = new AccountingEntry();
        e.setTenant(tenant); e.setJournal(journal); e.setFiscalYear(year);
        e.setReference(ref); e.setDescription(desc);
        e.setEntryDate(date != null ? date : LocalDate.now());
        e.setPartner(partner);
        return e;
    }

    private void addLine(AccountingEntry entry, AccountingAccount account,
                          String label, BigDecimal debit, BigDecimal credit) {
        AccountingEntryLine line = new AccountingEntryLine();
        line.setEntry(entry); line.setAccount(account); line.setLabel(label);
        line.setDebit(debit); line.setCredit(credit);
        entry.getLines().add(line);
    }

    private String safeRef(SupplierInvoice inv) {
        return inv.getInvoiceNumber() != null ? inv.getInvoiceNumber()
                : (inv.getId() != null ? inv.getId().toString() : "DRAFT");
    }

    private BigDecimal nvl(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }
    private LocalDate nvlDate(LocalDate d) { return d != null ? d : LocalDate.now(); }
}
