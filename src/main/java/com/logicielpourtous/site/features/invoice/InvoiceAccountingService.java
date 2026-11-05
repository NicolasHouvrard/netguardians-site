package com.logicielpourtous.site.features.invoice;

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
 * Génère automatiquement les écritures comptables lors des changements de statut de facture.
 *
 * SENT  → Journal VT : D 411 Clients / C 706 Prestations + C 445710 TVA collectée
 * PAID  → Journal BQ : D 512 Banque  / C 411 Clients
 * CANCELLED → Extourne du journal VT
 */
@Service
public class InvoiceAccountingService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceAccountingService.class);

    private final AccountingAccountRepository accountRepo;
    private final AccountingJournalRepository journalRepo;
    private final AccountingEntryRepository   entryRepo;
    private final AccountingService           accountingService;

    public InvoiceAccountingService(AccountingAccountRepository accountRepo,
                                    AccountingJournalRepository journalRepo,
                                    AccountingEntryRepository entryRepo,
                                    AccountingService accountingService) {
        this.accountRepo      = accountRepo;
        this.journalRepo      = journalRepo;
        this.entryRepo        = entryRepo;
        this.accountingService = accountingService;
    }

    /** Appelé quand la facture passe en SENT — écriture de vente dans journal VT. */
    @Transactional
    public void onInvoiceSent(Tenant tenant, Invoice invoice) {
        if (!isEligible(tenant, invoice)) return;
        String ref = "FAC-" + invoice.getInvoiceNumber() + "-VT";
        if (entryRepo.existsByReferenceAndTenant(ref, tenant)) return; // idempotent

        AccountingJournal vt = findJournal(tenant, "VT").orElse(null);
        FiscalYear year      = accountingService.getCurrentFiscalYear(tenant);
        if (vt == null || year == null) { log.warn("Comptabilité non initialisée pour tenant {}", tenant.getSchemaName()); return; }

        var clients    = findAccount(tenant, "411000").orElse(null);
        var prestations = findAccount(tenant, "706000").orElse(null);
        var tvaCollect = findAccount(tenant, "445710").orElse(null);
        if (clients == null || prestations == null || tvaCollect == null) { log.warn("Comptes 411/706/445710 manquants pour tenant {}", tenant.getSchemaName()); return; }

        BigDecimal ht  = invoice.getTotalHT()  != null ? invoice.getTotalHT()  : BigDecimal.ZERO;
        BigDecimal tva = invoice.getTotalTVA() != null ? invoice.getTotalTVA() : BigDecimal.ZERO;
        BigDecimal ttc = invoice.getTotalTTC() != null ? invoice.getTotalTTC() : BigDecimal.ZERO;

        AccountingEntry entry = buildEntry(tenant, vt, year, ref,
                "Facture " + invoice.getInvoiceNumber() + " — " + customerName(invoice),
                invoice.getInvoiceDate(), customerName(invoice));

        addLine(entry, clients,     "Clients — " + customerName(invoice), ttc, BigDecimal.ZERO);
        addLine(entry, prestations, "Prestations HT",                     BigDecimal.ZERO, ht);
        if (tva.compareTo(BigDecimal.ZERO) > 0) {
            addLine(entry, tvaCollect, "TVA collectée",                   BigDecimal.ZERO, tva);
        }

        entryRepo.save(entry);
        log.info("Écriture VT créée {} pour facture {}", ref, invoice.getInvoiceNumber());
    }

    /** Appelé quand la facture passe en PAID — écriture d'encaissement dans journal BQ. */
    @Transactional
    public void onInvoicePaid(Tenant tenant, Invoice invoice) {
        if (!isEligible(tenant, invoice)) return;
        String ref = "FAC-" + invoice.getInvoiceNumber() + "-BQ";
        if (entryRepo.existsByReferenceAndTenant(ref, tenant)) return;

        AccountingJournal bq = findJournal(tenant, "BQ").orElse(null);
        FiscalYear year      = accountingService.getCurrentFiscalYear(tenant);
        if (bq == null || year == null) { log.warn("Comptabilité non initialisée pour tenant {}", tenant.getSchemaName()); return; }

        var banque  = findAccount(tenant, "512000").orElse(null);
        var clients = findAccount(tenant, "411000").orElse(null);
        if (banque == null || clients == null) { log.warn("Comptes 512/411 manquants pour tenant {}", tenant.getSchemaName()); return; }

        BigDecimal ttc = invoice.getTotalTTC() != null ? invoice.getTotalTTC() : BigDecimal.ZERO;

        AccountingEntry entry = buildEntry(tenant, bq, year, ref,
                "Règlement " + invoice.getInvoiceNumber() + " — " + customerName(invoice),
                LocalDate.now(), customerName(invoice));

        addLine(entry, banque,  "Banque",                               ttc,           BigDecimal.ZERO);
        addLine(entry, clients, "Clients — " + customerName(invoice),  BigDecimal.ZERO, ttc);

        entryRepo.save(entry);
        log.info("Écriture BQ créée {} pour facture {}", ref, invoice.getInvoiceNumber());
    }

    /** Appelé quand la facture est CANCELLED — extourne de l'écriture VT. */
    @Transactional
    public void onInvoiceCancelled(Tenant tenant, Invoice invoice) {
        if (!isEligible(tenant, invoice)) return;
        String ref = "FAC-" + invoice.getInvoiceNumber() + "-AV";
        if (entryRepo.existsByReferenceAndTenant(ref, tenant)) return;

        AccountingJournal vt = findJournal(tenant, "VT").orElse(null);
        FiscalYear year      = accountingService.getCurrentFiscalYear(tenant);
        if (vt == null || year == null) return;

        var clients    = findAccount(tenant, "411000").orElse(null);
        var prestations = findAccount(tenant, "706000").orElse(null);
        var tvaCollect = findAccount(tenant, "445710").orElse(null);
        if (clients == null || prestations == null || tvaCollect == null) return;

        BigDecimal ht  = invoice.getTotalHT()  != null ? invoice.getTotalHT()  : BigDecimal.ZERO;
        BigDecimal tva = invoice.getTotalTVA() != null ? invoice.getTotalTVA() : BigDecimal.ZERO;
        BigDecimal ttc = invoice.getTotalTTC() != null ? invoice.getTotalTTC() : BigDecimal.ZERO;

        AccountingEntry entry = buildEntry(tenant, vt, year, ref,
                "Annulation " + invoice.getInvoiceNumber() + " — " + customerName(invoice),
                LocalDate.now(), customerName(invoice));

        // Extourne : sens inversé
        addLine(entry, prestations, "Annulation prestations HT", ht,           BigDecimal.ZERO);
        if (tva.compareTo(BigDecimal.ZERO) > 0) {
            addLine(entry, tvaCollect, "Annulation TVA collectée", tva,         BigDecimal.ZERO);
        }
        addLine(entry, clients,     "Annulation client",          BigDecimal.ZERO, ttc);

        entryRepo.save(entry);
        log.info("Extourne VT créée {} pour facture {}", ref, invoice.getInvoiceNumber());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private boolean isEligible(Tenant tenant, Invoice invoice) {
        return tenant != null
                && invoice != null
                && invoice.getType() == Invoice.InvoiceType.SALE
                && invoice.getTotalTTC() != null
                && invoice.getTotalTTC().compareTo(BigDecimal.ZERO) != 0;
    }

    private Optional<AccountingJournal> findJournal(Tenant tenant, String code) {
        return journalRepo.findByCodeAndTenant(code, tenant);
    }

    private Optional<AccountingAccount> findAccount(Tenant tenant, String number) {
        return accountRepo.findByNumberAndTenant(number, tenant);
    }

    private AccountingEntry buildEntry(Tenant tenant, AccountingJournal journal,
                                       FiscalYear year, String ref, String desc, LocalDate date, String partner) {
        AccountingEntry entry = new AccountingEntry();
        entry.setTenant(tenant);
        entry.setJournal(journal);
        entry.setFiscalYear(year);
        entry.setReference(ref);
        entry.setDescription(desc);
        entry.setPartner(partner);
        entry.setEntryDate(date != null ? date : LocalDate.now());
        return entry;
    }

    private void addLine(AccountingEntry entry, AccountingAccount account,
                         String label, BigDecimal debit, BigDecimal credit) {
        AccountingEntryLine line = new AccountingEntryLine();
        line.setEntry(entry);
        line.setAccount(account);
        line.setLabel(label);
        line.setDebit(debit);
        line.setCredit(credit);
        entry.getLines().add(line);
    }

    private String customerName(Invoice invoice) {
        return invoice.getCustomer() != null ? invoice.getCustomer().getCompanyName() : "—";
    }
}
