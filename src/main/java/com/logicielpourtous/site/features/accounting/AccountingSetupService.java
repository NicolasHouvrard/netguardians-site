package com.logicielpourtous.site.features.accounting;

import com.logicielpourtous.site.features.commun.Tenant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Initialise le Plan Comptable Général standard (PCG) pour un nouveau tenant ESN.
 * Appelé à la création d'un tenant ou via l'interface d'administration.
 */
@Service
public class AccountingSetupService {

    private final AccountingAccountRepository accountRepo;
    private final AccountingJournalRepository journalRepo;
    private final FiscalYearRepository fiscalYearRepo;
    private final QontoAccountingMappingRepository qontoMappingRepo;
    private final StripeAccountingMappingRepository stripeMappingRepo;

    public AccountingSetupService(AccountingAccountRepository accountRepo,
                                   AccountingJournalRepository journalRepo,
                                   FiscalYearRepository fiscalYearRepo,
                                   QontoAccountingMappingRepository qontoMappingRepo,
                                   StripeAccountingMappingRepository stripeMappingRepo) {
        this.accountRepo = accountRepo;
        this.journalRepo = journalRepo;
        this.fiscalYearRepo = fiscalYearRepo;
        this.qontoMappingRepo = qontoMappingRepo;
        this.stripeMappingRepo = stripeMappingRepo;
    }

    public boolean isInitialized(Tenant tenant) {
        return !accountRepo.findByTenantOrderByNumberAsc(tenant).isEmpty();
    }

    @Transactional
    public void initializeForTenant(Tenant tenant) {
        if (isInitialized(tenant)) return;

        // ── Exercice fiscal courant ────────────────────────────────────────────
        int year = LocalDate.now().getYear();
        FiscalYear fy = new FiscalYear();
        fy.setTenant(tenant);
        fy.setLabel("Exercice " + year);
        fy.setStartDate(LocalDate.of(year, 1, 1));
        fy.setEndDate(LocalDate.of(year, 12, 31));
        fy.setCurrent(true);
        fiscalYearRepo.save(fy);

        // ── Journaux standard ─────────────────────────────────────────────────
        seedJournal(tenant, "AN", "À Nouveau",           JournalType.OPENING);
        seedJournal(tenant, "VT", "Ventes",              JournalType.SALES);
        seedJournal(tenant, "AC", "Achats",              JournalType.PURCHASES);
        seedJournal(tenant, "BQ", "Banque",              JournalType.BANK);
        seedJournal(tenant, "CA", "Caisse",              JournalType.CASH);
        seedJournal(tenant, "OD", "Opérations diverses", JournalType.MISC);
        seedJournal(tenant, "SA", "Salaires",            JournalType.PAYROLL);
        seedJournal(tenant, "QTO","Qonto",               JournalType.BANK);
        seedJournal(tenant, "STR","Stripe",              JournalType.BANK);

        // ── Plan Comptable Général — Classe 1 ─────────────────────────────────
        seedAccount(tenant, "101000", "Capital social");
        seedAccount(tenant, "106100", "Réserve légale");
        seedAccount(tenant, "110000", "Report à nouveau (solde créditeur)");
        seedAccount(tenant, "119000", "Report à nouveau (solde débiteur)");
        seedAccount(tenant, "120000", "Résultat de l'exercice (bénéfice)");
        seedAccount(tenant, "129000", "Résultat de l'exercice (perte)");
        seedAccount(tenant, "164000", "Emprunts auprès des établissements de crédit");

        // ── Classe 2 ──────────────────────────────────────────────────────────
        seedAccount(tenant, "205000", "Logiciels");
        seedAccount(tenant, "218300", "Matériel informatique");
        seedAccount(tenant, "280500", "Amortissements — Logiciels");
        seedAccount(tenant, "281830", "Amortissements — Matériel informatique");

        // ── Classe 4 ──────────────────────────────────────────────────────────
        seedAccount(tenant, "401000", "Fournisseurs");
        seedAccount(tenant, "404000", "Fournisseurs d'immobilisations");
        seedAccount(tenant, "408000", "Fournisseurs — Factures non parvenues");
        seedAccount(tenant, "411000", "Clients");
        seedAccount(tenant, "413000", "Clients — Effets à recevoir");
        seedAccount(tenant, "416000", "Clients douteux ou litigieux");
        seedAccount(tenant, "418000", "Clients — Produits non encore facturés");
        seedAccount(tenant, "419000", "Clients créditeurs — Avoirs à établir");
        seedAccount(tenant, "421000", "Personnel — Rémunérations dues");
        seedAccount(tenant, "422000", "Comités d'entreprise");
        seedAccount(tenant, "425000", "Personnel — Avances et acomptes");
        seedAccount(tenant, "428000", "Personnel — Charges à payer");
        seedAccount(tenant, "431000", "Sécurité sociale");
        seedAccount(tenant, "437000", "Autres organismes sociaux");
        seedAccount(tenant, "438000", "Charges sociales sur congés payés");
        seedAccount(tenant, "441000", "État — Subventions à recevoir");
        seedAccount(tenant, "444000", "État — Impôts sur les bénéfices");
        seedAccount(tenant, "445500", "TVA à décaisser");
        seedAccount(tenant, "445620", "TVA déductible — Biens et services");
        seedAccount(tenant, "445710", "TVA collectée");
        seedAccount(tenant, "447000", "Autres impôts, taxes et versements assimilés");
        seedAccount(tenant, "455000", "Associés — Comptes courants");
        seedAccount(tenant, "467000", "Autres comptes débiteurs ou créditeurs");

        // ── Classe 5 ──────────────────────────────────────────────────────────
        seedAccount(tenant, "512000", "Banque");
        seedAccount(tenant, "512100", "Qonto");
        seedAccount(tenant, "512200", "Stripe");
        seedAccount(tenant, "530000", "Caisse");
        seedAccount(tenant, "580000", "Virements internes");

        // ── Classe 6 ──────────────────────────────────────────────────────────
        seedAccount(tenant, "601000", "Achats stockés — Matières premières");
        seedAccount(tenant, "606000", "Achats non stockés de matières et fournitures");
        seedAccount(tenant, "606100", "Fournitures de bureau");
        seedAccount(tenant, "606200", "Petits équipements");
        seedAccount(tenant, "611000", "Sous-traitance générale");
        seedAccount(tenant, "613000", "Locations");
        seedAccount(tenant, "613500", "Locations matériel informatique");
        seedAccount(tenant, "615000", "Entretien et réparations");
        seedAccount(tenant, "616000", "Primes d'assurance");
        seedAccount(tenant, "618000", "Divers (documentation, formation)");
        seedAccount(tenant, "621000", "Personnel extérieur à l'entreprise");
        seedAccount(tenant, "622600", "Honoraires");
        seedAccount(tenant, "623000", "Publicité, publications");
        seedAccount(tenant, "625100", "Voyages et déplacements");
        seedAccount(tenant, "625600", "Missions");
        seedAccount(tenant, "626000", "Frais postaux et de télécommunications");
        seedAccount(tenant, "627000", "Services bancaires et assimilés");
        seedAccount(tenant, "628100", "Frais Stripe / commissions bancaires");
        seedAccount(tenant, "635000", "Autres impôts, taxes (CFE, CVAE...)");
        seedAccount(tenant, "641000", "Rémunérations du personnel");
        seedAccount(tenant, "645000", "Charges de sécurité sociale");
        seedAccount(tenant, "648000", "Autres charges de personnel");
        seedAccount(tenant, "661000", "Charges d'intérêts");
        seedAccount(tenant, "671000", "Charges exceptionnelles sur opérations de gestion");
        seedAccount(tenant, "681000", "Dotations aux amortissements");

        // ── Classe 7 ──────────────────────────────────────────────────────────
        seedAccount(tenant, "706000", "Prestations de services");
        seedAccount(tenant, "706100", "Régie / facturation au temps passé");
        seedAccount(tenant, "706200", "Forfait / projet");
        seedAccount(tenant, "706300", "Maintenance et support");
        seedAccount(tenant, "706400", "Licences et abonnements");
        seedAccount(tenant, "708000", "Produits des activités annexes");
        seedAccount(tenant, "709000", "Rabais, remises, ristournes accordés");
        seedAccount(tenant, "756000", "Produits financiers");
        seedAccount(tenant, "771000", "Produits exceptionnels sur opérations de gestion");

        // ── Comptes supplémentaires ────────────────────────────────────────────
        seedAccount(tenant, "666000", "Pertes de change");
        seedAccount(tenant, "766000", "Gains de change");

        // ── Mappings Qonto par défaut ──────────────────────────────────────────
        ensureMappingsComplete(tenant);

        // ── Mappings Stripe par défaut ────────────────────────────────────────
        seedStripeMapping(tenant, "payment_intent.succeeded", "Encaissement Stripe",    "512200", "706000");
        seedStripeMapping(tenant, "payout.paid",              "Virement Stripe → Banque","512000", "512200");
        seedStripeMapping(tenant, "charge.refunded",          "Remboursement Stripe",    "709000", "512200");
    }

    /**
     * Idempotent — ajoute les mappings Qonto manquants pour un tenant déjà initialisé.
     * Appelable à tout moment sans risque de doublons.
     */
    @Transactional
    public void ensureMappingsComplete(Tenant tenant) {
        // Encaissements
        seedQontoMapping(tenant, "income:credit",           "Encaissement client",          "512100", "411000");
        seedQontoMapping(tenant, "transfer:credit",         "Virement entrant",             "512100", "411000");
        seedQontoMapping(tenant, "swift_income:credit",     "Virement SWIFT entrant",       "512100", "411000");
        seedQontoMapping(tenant, "cheque:credit",           "Remise de chèque",             "512100", "411000");
        seedQontoMapping(tenant, "card:credit",             "Remboursement carte",          "512100", "606000");
        seedQontoMapping(tenant, "direct_debit:credit",     "Remboursement prélèvement",    "512100", "613000");
        seedQontoMapping(tenant, "recall:credit",           "Retour de virement",           "512100", "467000");
        seedQontoMapping(tenant, "fx:credit",               "Gain de change",               "512100", "766000");
        // Décaissements
        seedQontoMapping(tenant, "transfer:debit",          "Virement sortant fournisseur", "401000", "512100");
        seedQontoMapping(tenant, "swift_transfer:debit",    "Virement SWIFT sortant",       "401000", "512100");
        seedQontoMapping(tenant, "card:debit",              "Paiement carte",               "606000", "512100");
        seedQontoMapping(tenant, "direct_debit:debit",      "Prélèvement",                  "613000", "512100");
        seedQontoMapping(tenant, "payroll:debit",           "Salaires",                     "641000", "512100");
        seedQontoMapping(tenant, "qonto_fee:debit",         "Frais Qonto",                  "627000", "512100");
        seedQontoMapping(tenant, "tax_collector:debit",     "Paiement impôts / taxes",      "447000", "512100");
        seedQontoMapping(tenant, "fx:debit",                "Perte de change",              "666000", "512100");
        seedQontoMapping(tenant, "recall:debit",            "Virement rappelé",             "467000", "512100");
        // internal_transfer volontairement absent : skippé explicitement dans QontoService
    }

    private void seedJournal(Tenant tenant, String code, String label, JournalType type) {
        if (journalRepo.existsByCodeAndTenant(code, tenant)) return;
        AccountingJournal j = new AccountingJournal();
        j.setTenant(tenant); j.setCode(code); j.setLabel(label); j.setType(type);
        journalRepo.save(j);
    }

    private void seedAccount(Tenant tenant, String number, String label) {
        if (accountRepo.existsByNumberAndTenant(number, tenant)) return;
        AccountingAccount a = new AccountingAccount();
        a.setTenant(tenant); a.setNumber(number); a.setLabel(label);
        accountRepo.save(a);
    }

    private void seedQontoMapping(Tenant tenant, String key, String label, String debit, String credit) {
        if (qontoMappingRepo.findByMappingKeyAndTenant(key, tenant).isPresent()) return;
        QontoAccountingMapping m = new QontoAccountingMapping();
        m.setTenant(tenant); m.setMappingKey(key); m.setLabel(label);
        m.setDebitAccountNumber(debit); m.setCreditAccountNumber(credit);
        qontoMappingRepo.save(m);
    }

    private void seedStripeMapping(Tenant tenant, String eventType, String label, String debit, String credit) {
        if (stripeMappingRepo.findByEventTypeAndTenant(eventType, tenant).isPresent()) return;
        StripeAccountingMapping m = new StripeAccountingMapping();
        m.setTenant(tenant); m.setEventType(eventType); m.setLabel(label);
        m.setDebitAccountNumber(debit); m.setCreditAccountNumber(credit);
        stripeMappingRepo.save(m);
    }
}
