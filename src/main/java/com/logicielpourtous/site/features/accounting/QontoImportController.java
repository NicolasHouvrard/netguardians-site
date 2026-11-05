package com.logicielpourtous.site.features.accounting;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.purchase.MindeeService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

@Controller
@RequestMapping("/intra/accounting/qonto")
public class QontoImportController {

    private final QontoService qontoService;
    private final QontoConfigRepository configRepo;
    private final QontoAccountingMappingRepository mappingRepo;
    private final QontoTransactionRepository txRepo;
    private final AccountingAccountRepository accountRepo;
    private final AccountingEntryRepository entryRepo;
    private final AccountingEntryLineRepository lineRepo;
    private final AccountingJournalRepository journalRepo;
    private final AccountingService accountingService;
    private final AccountingSetupService setupService;
    private final MindeeService mindeeService;
    private final QontoMatchingService matchingService;

    @Value("${app.upload-dir}")
    private String uploadDir;

    public QontoImportController(QontoService qontoService,
                                  QontoConfigRepository configRepo,
                                  QontoAccountingMappingRepository mappingRepo,
                                  QontoTransactionRepository txRepo,
                                  AccountingAccountRepository accountRepo,
                                  AccountingEntryRepository entryRepo,
                                  AccountingEntryLineRepository lineRepo,
                                  AccountingJournalRepository journalRepo,
                                  AccountingService accountingService,
                                  AccountingSetupService setupService,
                                  MindeeService mindeeService,
                                  QontoMatchingService matchingService) {
        this.qontoService = qontoService;
        this.configRepo = configRepo;
        this.mappingRepo = mappingRepo;
        this.txRepo = txRepo;
        this.accountRepo = accountRepo;
        this.entryRepo = entryRepo;
        this.lineRepo = lineRepo;
        this.journalRepo = journalRepo;
        this.accountingService = accountingService;
        this.setupService = setupService;
        this.mindeeService = mindeeService;
        this.matchingService = matchingService;
    }

    // ── Paramétrage ──────────────────────────────────────────────────────────

    @GetMapping
    public String config(@AuthenticationPrincipal CustomUserDetails principal, Model model) {
        Tenant tenant = principal.getTenant();
        if (setupService.isInitialized(tenant)) {
            setupService.ensureMappingsComplete(tenant);
        }
        model.addAttribute("configs", configRepo.findAllByTenantOrderByIdAsc(tenant));
        model.addAttribute("anyConfigured", configRepo.findAllByTenantOrderByIdAsc(tenant).stream().anyMatch(QontoConfig::isConfigured));
        model.addAttribute("mappings", mappingRepo.findByTenantOrderByMappingKeyAsc(tenant));
        model.addAttribute("accounts", accountRepo.findByTenantAndActiveTrueOrderByNumberAsc(tenant));
        return "intra/accounting/qonto/config";
    }

    @PostMapping("/config/save")
    @Transactional
    public String saveConfig(@AuthenticationPrincipal CustomUserDetails principal,
                              @RequestParam(required = false) Long configId,
                              @RequestParam(required = false) String label,
                              @RequestParam(required = false) String organizationSlug,
                              @RequestParam(required = false) String secretKey,
                              @RequestParam(required = false) String iban,
                              @RequestParam(required = false) String bankAccountNumber,
                              @RequestParam(defaultValue = "false") boolean syncEnabled,
                              @RequestParam(defaultValue = "8") int syncStartHour,
                              @RequestParam(defaultValue = "18") int syncEndHour,
                              @RequestParam(defaultValue = "60") int syncIntervalMinutes,
                              RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        QontoConfig config;
        if (configId != null) {
            config = configRepo.findById(configId)
                    .filter(c -> c.getTenant().getSchemaName().equals(tenant.getSchemaName()))
                    .orElseGet(QontoConfig::new);
        } else {
            config = new QontoConfig();
        }
        config.setTenant(tenant);
        config.setLabel(label != null && !label.isBlank() ? label : "Compte Qonto");
        if (organizationSlug != null && !organizationSlug.isBlank()) config.setOrganizationSlug(organizationSlug);
        if (secretKey != null && !secretKey.isBlank() && !secretKey.startsWith("••")) config.setSecretKey(secretKey);
        if (iban != null) config.setIban(iban.isBlank() ? null : iban);
        config.setBankAccountNumber(bankAccountNumber != null && !bankAccountNumber.isBlank() ? bankAccountNumber : null);
        config.setActive(true);
        config.setSyncEnabled(syncEnabled);
        config.setSyncStartHour(Math.max(0, Math.min(23, syncStartHour)));
        config.setSyncEndHour(Math.max(1, Math.min(24, syncEndHour)));
        config.setSyncIntervalMinutes(Math.max(15, syncIntervalMinutes));
        configRepo.save(config);
        ra.addFlashAttribute("successMessage", "Configuration Qonto sauvegardée.");
        return "redirect:/intra/accounting/qonto";
    }

    @PostMapping("/config/{configId}/discover")
    public String discoverAccounts(@PathVariable Long configId,
                                    @AuthenticationPrincipal CustomUserDetails principal,
                                    RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        QontoConfig master = configRepo.findById(configId)
            .filter(c -> c.getTenant().getSchemaName().equals(tenant.getSchemaName()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!master.isConfigured()) {
            ra.addFlashAttribute("errorMessage", "Configuration incomplète — slug et clé secrète requis.");
            return "redirect:/intra/accounting/qonto";
        }
        try {
            List<QontoOrganizationDTO.BankAccount> bankAccounts = qontoService.fetchBankAccounts(master);
            List<String> existingIbans = configRepo.findAllByTenantOrderByIdAsc(tenant).stream()
                .map(QontoConfig::getIban).filter(i -> i != null && !i.isBlank()).toList();
            int created = 0;
            for (QontoOrganizationDTO.BankAccount ba : bankAccounts) {
                if (ba.iban() == null || ba.iban().isBlank()) continue;
                if (existingIbans.contains(ba.iban())) continue;
                QontoConfig cfg = new QontoConfig();
                cfg.setTenant(tenant);
                cfg.setOrganizationSlug(master.getOrganizationSlug());
                cfg.setSecretKey(master.getSecretKey());
                cfg.setIban(ba.iban());
                cfg.setLabel(ba.name() != null && !ba.name().isBlank()
                    ? ba.name()
                    : "Compte " + ba.iban().substring(Math.max(0, ba.iban().length() - 4)));
                cfg.setActive(true);
                configRepo.save(cfg);
                created++;
            }
            ra.addFlashAttribute("successMessage",
                created > 0 ? created + " nouveau(x) compte(s) Qonto découvert(s)."
                            : "Tous les comptes sont déjà enregistrés.");
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", "Erreur découverte Qonto : " + e.getMessage());
        }
        return "redirect:/intra/accounting/qonto";
    }

    @PostMapping("/config/{configId}/delete")
    public String deleteConfig(@PathVariable Long configId,
                               @AuthenticationPrincipal CustomUserDetails principal,
                               RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        configRepo.findById(configId)
                .filter(c -> c.getTenant().getSchemaName().equals(tenant.getSchemaName()))
                .ifPresent(configRepo::delete);
        ra.addFlashAttribute("successMessage", "Compte Qonto supprimé.");
        return "redirect:/intra/accounting/qonto";
    }

    @PostMapping("/mappings/save")
    @Transactional
    public String saveMapping(@RequestParam Long id,
                               @RequestParam String debitAccountNumber,
                               @RequestParam String creditAccountNumber,
                               @RequestParam(defaultValue = "false") boolean active,
                               RedirectAttributes ra) {
        mappingRepo.findById(id).ifPresent(m -> {
            m.setDebitAccountNumber(debitAccountNumber);
            m.setCreditAccountNumber(creditAccountNumber);
            m.setActive(active);
            mappingRepo.save(m);
        });
        ra.addFlashAttribute("successMessage", "Mapping mis à jour.");
        return "redirect:/intra/accounting/qonto";
    }

    // ── Création rapide d'un compte PCG ──────────────────────────────────────

    @PostMapping("/config/create-account")
    @Transactional
    public String createBankAccount(@AuthenticationPrincipal CustomUserDetails principal,
                                     @RequestParam String number,
                                     @RequestParam String label,
                                     RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        if (number.isBlank() || label.isBlank()) {
            ra.addFlashAttribute("errorMessage", "Numéro et libellé requis.");
            return "redirect:/intra/accounting/qonto";
        }
        if (accountRepo.existsByNumberAndTenant(number.trim(), tenant)) {
            ra.addFlashAttribute("errorMessage", "Le compte " + number.trim() + " existe déjà.");
            return "redirect:/intra/accounting/qonto";
        }
        AccountingAccount acc = new AccountingAccount();
        acc.setTenant(tenant);
        acc.setNumber(number.trim());
        acc.setLabel(label.trim());
        acc.setActive(true);
        acc.setPostable(true);
        accountRepo.save(acc);
        ra.addFlashAttribute("successMessage", "Compte " + number.trim() + " — " + label.trim() + " créé.");
        return "redirect:/intra/accounting/qonto";
    }

    // ── Liste des transactions ────────────────────────────────────────────────

    @GetMapping("/transactions")
    public String transactions(@AuthenticationPrincipal CustomUserDetails principal,
                                @RequestParam(defaultValue = "unreconciled") String filter,
                                Model model) {
        Tenant tenant = principal.getTenant();
        var transactions = switch (filter) {
            case "reconciled" -> txRepo.findTop200ByTenantAndReconciledTrueOrderByImportedAtDesc(tenant);
            case "skipped"    -> txRepo.findTop200ByTenantAndReconciledFalseAndImportStatusOrderByImportedAtDesc(tenant, "SKIPPED");
            case "all"        -> txRepo.findTop200ByTenantOrderByImportedAtDesc(tenant);
            default           -> txRepo.findTop200ByTenantAndReconciledFalseOrderByImportedAtDesc(tenant);
        };
        model.addAttribute("filter", filter);
        model.addAttribute("transactions", transactions);
        model.addAttribute("countImported",     txRepo.countByTenantAndImportStatus(tenant, "IMPORTED"));
        model.addAttribute("countSkipped",      txRepo.countByTenantAndImportStatus(tenant, "SKIPPED"));
        model.addAttribute("countErrors",       txRepo.countByTenantAndImportStatus(tenant, "ERROR"));
        model.addAttribute("countUnreconciled", txRepo.countByTenantAndReconciledFalse(tenant));
        model.addAttribute("configs",           configRepo.findAllByTenantOrderByIdAsc(tenant));
        model.addAttribute("anyConfigured",     configRepo.findAllByTenantOrderByIdAsc(tenant).stream().anyMatch(QontoConfig::isConfigured));
        return "intra/accounting/qonto/transactions";
    }

    // ── Détail d'une transaction ──────────────────────────────────────────────

    @GetMapping("/transactions/{id}")
    public String transactionDetail(@PathVariable Long id,
                                     @AuthenticationPrincipal CustomUserDetails principal,
                                     Model model) {
        Tenant tenant = principal.getTenant();
        QontoTransaction tx = txRepo.findByIdAndTenant(id, tenant)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("tx", tx);
        model.addAttribute("accounts", accountRepo.findByTenantAndActiveTrueOrderByNumberAsc(tenant));
        if (tx.getAccountingEntryId() != null) {
            entryRepo.findById(tx.getAccountingEntryId())
                .ifPresent(e -> model.addAttribute("entry", e));
        }
        // Suggestion comptable depuis le mapping
        if (tx.getOperationType() != null && tx.getSide() != null) {
            String mappingKey = tx.getOperationType() + ":" + tx.getSide();
            mappingRepo.findByMappingKeyAndTenantAndActiveTrue(mappingKey, tenant).ifPresent(m -> {
                model.addAttribute("suggestedDebitNumber", m.getDebitAccountNumber());
                model.addAttribute("suggestedCreditNumber", m.getCreditAccountNumber());
            });
        }
        return "intra/accounting/qonto/transaction-detail";
    }

    @PostMapping("/transactions/{id}/create-entry")
    @Transactional
    public String createEntry(@PathVariable Long id,
                               @RequestParam String debitAccountNumber,
                               @RequestParam String creditAccountNumber,
                               @RequestParam(required = false) String label,
                               @RequestParam(required = false) String partner,
                               @RequestParam(defaultValue = "false") boolean doReconcile,
                               @AuthenticationPrincipal CustomUserDetails principal,
                               RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        txRepo.findByIdAndTenant(id, tenant).ifPresent(tx -> {
            try {
                AccountingJournal journal = journalRepo.findByCodeAndTenant("QTO", tenant)
                        .orElseGet(() -> journalRepo.findByCodeAndTenant("BQ", tenant)
                        .orElseThrow(() -> new IllegalStateException("Journal QTO ou BQ introuvable")));
                FiscalYear year = accountingService.getCurrentFiscalYear(tenant);
                if (year == null) throw new IllegalStateException("Aucun exercice courant");

                AccountingAccount debitAcc = accountRepo.findByNumberAndTenant(debitAccountNumber.trim(), tenant)
                        .orElseThrow(() -> new IllegalStateException("Compte débit introuvable: " + debitAccountNumber));
                AccountingAccount creditAcc = accountRepo.findByNumberAndTenant(creditAccountNumber.trim(), tenant)
                        .orElseThrow(() -> new IllegalStateException("Compte crédit introuvable: " + creditAccountNumber));

                String lineLabel = (label != null && !label.isBlank()) ? label : (tx.getLabel() != null ? tx.getLabel() : "Transaction Qonto");
                String entryPartner = (partner != null && !partner.isBlank()) ? partner : tx.getLabel();
                LocalDate date = tx.getSettledAt() != null ? tx.getSettledAt() : LocalDate.now();
                BigDecimal amount = tx.getAmount() != null ? tx.getAmount() : BigDecimal.ZERO;

                AccountingEntry entry = new AccountingEntry();
                entry.setTenant(tenant);
                entry.setJournal(journal);
                entry.setFiscalYear(year);
                entry.setEntryDate(date);
                entry.setReference(accountingService.generateReference(journal, year));
                entry.setDescription(lineLabel);
                entry.setPartner(entryPartner);

                AccountingEntryLine dl = new AccountingEntryLine();
                dl.setAccount(debitAcc); dl.setLabel(lineLabel);
                dl.setDebit(amount); dl.setCredit(BigDecimal.ZERO); dl.setEntry(entry);
                entry.getLines().add(dl);

                AccountingEntryLine cl = new AccountingEntryLine();
                cl.setAccount(creditAcc); cl.setLabel(lineLabel);
                cl.setDebit(BigDecimal.ZERO); cl.setCredit(amount); cl.setEntry(entry);
                entry.getLines().add(cl);

                entryRepo.save(entry);
                tx.setAccountingEntryId(entry.getId());
                if (doReconcile) tx.setReconciled(true);
                txRepo.save(tx);
            } catch (Exception e) {
                ra.addFlashAttribute("errorMessage", "Erreur : " + e.getMessage());
            }
        });
        ra.addFlashAttribute("successMessage", "Écriture créée avec succès.");
        return "redirect:/intra/accounting/qonto/transactions/" + id;
    }

    @PostMapping("/transactions/{id}/reactivate")
    @Transactional
    public String reactivate(@PathVariable Long id,
                              @AuthenticationPrincipal CustomUserDetails principal,
                              RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        txRepo.findByIdAndTenant(id, tenant).ifPresent(tx -> {
            tx.setImportStatus("IMPORTED");
            tx.setErrorMessage(null);
            txRepo.save(tx);
        });
        ra.addFlashAttribute("successMessage", "Transaction marquée comme importée — vous pouvez maintenant créer une écriture.");
        return "redirect:/intra/accounting/qonto/transactions/" + id;
    }

    @PostMapping("/transactions/{id}/detach")
    @Transactional
    public String detach(@PathVariable Long id,
                         @AuthenticationPrincipal CustomUserDetails principal,
                         RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        txRepo.findByIdAndTenant(id, tenant).ifPresent(tx -> {
            tx.setAccountingEntryId(null);
            tx.setReconciled(false);
            txRepo.save(tx);
        });
        ra.addFlashAttribute("successMessage", "Écriture détachée — la transaction est à nouveau à rapprocher.");
        return "redirect:/intra/accounting/qonto/transactions/" + id;
    }

    // ── Ré-attribution des comptes PCG via API Qonto ──────────────────────────

    @PostMapping("/transactions/reattribute-bank-accounts")
    public String reattributeBankAccounts(@AuthenticationPrincipal CustomUserDetails principal,
                                           @RequestParam(defaultValue = "90") String days,
                                           RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        try {
            int daysInt = parseDays(days);
            int updated = qontoService.reattributeBankAccounts(tenant, daysInt);
            ra.addFlashAttribute("successMessage",
                    "Ré-attribution terminée — " + updated + " transaction(s) vérifiées.");
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", "Erreur Qonto : " + e.getMessage());
        }
        return "redirect:/intra/accounting/qonto/transactions";
    }

    // ── Rapprochement ─────────────────────────────────────────────────────────

    @PostMapping("/transactions/{id}/reconcile")
    @Transactional
    public String reconcile(@PathVariable Long id,
                             @AuthenticationPrincipal CustomUserDetails principal,
                             @RequestParam(defaultValue = "unreconciled") String filter,
                             @RequestParam(required = false) String redirectTo,
                             RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        txRepo.findByIdAndTenant(id, tenant).ifPresent(tx -> {
            tx.setReconciled(!tx.isReconciled());
            txRepo.save(tx);
        });
        if (redirectTo != null && !redirectTo.isBlank()) return "redirect:" + redirectTo;
        return "redirect:/intra/accounting/qonto/transactions?filter=" + filter;
    }

    @PostMapping("/transactions/{id}/reconcile-detail")
    @Transactional
    public String reconcileFromDetail(@PathVariable Long id,
                                       @AuthenticationPrincipal CustomUserDetails principal) {
        Tenant tenant = principal.getTenant();
        txRepo.findByIdAndTenant(id, tenant).ifPresent(tx -> {
            tx.setReconciled(!tx.isReconciled());
            txRepo.save(tx);
        });
        return "redirect:/intra/accounting/qonto/transactions/" + id;
    }

    // ── Re-affectation des comptes ────────────────────────────────────────────

    @PostMapping("/transactions/{id}/reaffect")
    @Transactional
    public String reaffect(@PathVariable Long id,
                            @RequestParam Map<String, String> params,
                            @RequestParam(defaultValue = "false") boolean doReconcile,
                            @AuthenticationPrincipal CustomUserDetails principal,
                            RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        txRepo.findByIdAndTenant(id, tenant).ifPresent(tx -> {
            if (tx.getAccountingEntryId() != null) {
                params.entrySet().stream()
                    .filter(e -> e.getKey().startsWith("line_"))
                    .forEach(e -> {
                        try {
                            Long lineId = Long.parseLong(e.getKey().substring(5));
                            lineRepo.findById(lineId).ifPresent(line -> {
                                if (line.getEntry().getTenant().getSchemaName().equals(tenant.getSchemaName())) {
                                    accountRepo.findByNumberAndTenant(e.getValue(), tenant)
                                        .ifPresent(line::setAccount);
                                    lineRepo.save(line);
                                }
                            });
                        } catch (NumberFormatException ignored) {}
                    });
            }
            if (doReconcile) {
                tx.setReconciled(true);
                txRepo.save(tx);
            }
        });
        ra.addFlashAttribute("successMessage", doReconcile
            ? "Affectation mise à jour et transaction rapprochée."
            : "Affectation comptable mise à jour.");
        return "redirect:/intra/accounting/qonto/transactions/" + id;
    }

    // ── Synchronisation ───────────────────────────────────────────────────────

    @PostMapping("/sync")
    public String sync(@AuthenticationPrincipal CustomUserDetails principal,
                       @RequestParam(required = false) Long configId,
                       @RequestParam(defaultValue = "30") String days,
                       RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        try {
            int daysInt = parseDays(days);
            QontoService.SyncResult result;
            if (configId != null) {
                QontoConfig config = configRepo.findById(configId)
                        .filter(c -> c.getTenant().getSchemaName().equals(tenant.getSchemaName()))
                        .orElseThrow(() -> new IllegalStateException("Configuration introuvable"));
                result = qontoService.syncFromQontoConfig(tenant, config, daysInt);
            } else {
                result = qontoService.syncFromQonto(tenant, daysInt);
            }
            ra.addFlashAttribute("successMessage",
                    "Sync Qonto terminé — " + result.imported() + " importé(s), " +
                    result.skipped() + " ignoré(s), " + result.errors() + " erreur(s).");
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", "Erreur inattendue : " + e.getMessage());
        }
        return "redirect:/intra/accounting/qonto/transactions";
    }

    // ── Visualisation pièce jointe Qonto ─────────────────────────────────────

    @GetMapping("/transactions/{id}/attachment/{attachmentId}")
    public String viewAttachment(@PathVariable Long id,
                                  @PathVariable String attachmentId,
                                  @AuthenticationPrincipal CustomUserDetails principal) {
        Tenant tenant = principal.getTenant();
        QontoTransaction tx = txRepo.findByIdAndTenant(id, tenant)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        if (tx.getAttachmentIds() == null
                || !List.of(tx.getAttachmentIds().split(",")).stream().map(String::trim).toList().contains(attachmentId.trim())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Pièce jointe non associée à cette transaction");
        }

        QontoConfig config = qontoService.getFirstConfigured(tenant);
        if (config == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Qonto non configuré");
        }

        String fileUrl = qontoService.fetchAttachmentUrl(config, attachmentId.trim());
        if (fileUrl == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "URL de pièce jointe introuvable");
        }

        return "redirect:" + fileUrl;
    }

    // ── Analyse Mindee depuis pièce jointe Qonto ──────────────────────────────

    @PostMapping("/transactions/{id}/analyze-invoice")
    public String analyzeInvoice(@PathVariable Long id,
                                  @AuthenticationPrincipal CustomUserDetails principal,
                                  HttpSession session,
                                  RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        QontoTransaction tx = txRepo.findByIdAndTenant(id, tenant)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        String ids = tx.getAttachmentIds();
        if (ids == null || ids.isBlank()) {
            ra.addFlashAttribute("errorMessage", "Cette transaction n'a aucune pièce jointe.");
            return "redirect:/intra/accounting/qonto/transactions/" + id;
        }
        if (!mindeeService.isConfigured()) {
            ra.addFlashAttribute("errorMessage", "Clé API Mindee non configurée (mindee.api-key).");
            return "redirect:/intra/accounting/qonto/transactions/" + id;
        }

        QontoConfig config = qontoService.getFirstConfigured(tenant);
        if (config == null) {
            ra.addFlashAttribute("errorMessage", "Qonto non configuré.");
            return "redirect:/intra/accounting/qonto/transactions/" + id;
        }

        try {
            String firstId = ids.split(",")[0].trim();
            String fileUrl = qontoService.fetchAttachmentUrl(config, firstId);
            if (fileUrl == null) {
                ra.addFlashAttribute("errorMessage", "Impossible de récupérer l'URL de la pièce jointe Qonto.");
                return "redirect:/intra/accounting/qonto/transactions/" + id;
            }

            // Télécharger le fichier depuis l'URL S3 de Qonto
            Path dir = Paths.get(uploadDir, "supplier-invoices", tenant.getSchemaName());
            Files.createDirectories(dir);
            String filename = firstId + ".pdf";
            Path dest = dir.resolve(filename);

            RestClient http = RestClient.create();
            byte[] bytes = http.get().uri(fileUrl).retrieve().body(byte[].class);
            if (bytes == null || bytes.length == 0) {
                ra.addFlashAttribute("errorMessage", "Fichier vide ou inaccessible.");
                return "redirect:/intra/accounting/qonto/transactions/" + id;
            }
            Files.write(dest, bytes);

            MindeeService.MindeeResult result = mindeeService.parseInvoice(dest.toFile());
            session.setAttribute("mindeeResult", result);
            session.setAttribute("mindeeFilePath", "supplier-invoices/" + tenant.getSchemaName() + "/" + filename);
            session.setAttribute("mindeeOriginalFilename", filename);
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", "Erreur analyse Mindee : " + e.getMessage());
            return "redirect:/intra/accounting/qonto/transactions/" + id;
        }

        return "redirect:/intra/supplier-invoices/upload?qontoTxId=" + id;
    }

    // ── Rapprochement automatique manuel ──────────────────────────────────────

    @PostMapping("/match")
    public String matchTransactions(@AuthenticationPrincipal CustomUserDetails principal,
                                    RedirectAttributes ra) {
        Tenant tenant = principal.getTenant();
        int matched = matchingService.matchUnreconciled(tenant);
        ra.addFlashAttribute("successMessage",
                matched > 0 ? matched + " transaction(s) rapprochée(s) automatiquement."
                            : "Aucune correspondance trouvée.");
        return "redirect:/intra/accounting/qonto/transactions";
    }

    private static int parseDays(String days) {
        if ("ytd".equals(days)) {
            java.time.LocalDate jan1 = java.time.LocalDate.of(java.time.LocalDate.now().getYear(), 1, 1);
            return (int) java.time.temporal.ChronoUnit.DAYS.between(jan1, java.time.LocalDate.now()) + 1;
        }
        return Integer.parseInt(days);
    }
}
