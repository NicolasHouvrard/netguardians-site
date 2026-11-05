package com.logicielpourtous.site.features.accounting;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.logicielpourtous.site.features.commun.Tenant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class QontoService {

    private static final String QONTO_BASE_URL = "https://thirdparty.qonto.com/v2";

    private final QontoConfigRepository configRepo;
    private final QontoAccountingMappingRepository mappingRepo;
    private final QontoTransactionRepository txRepo;
    private final AccountingEntryRepository entryRepo;
    private final AccountingJournalRepository journalRepo;
    private final AccountingAccountRepository accountRepo;
    private final AccountingService accountingService;

    public QontoService(QontoConfigRepository configRepo,
                        QontoAccountingMappingRepository mappingRepo,
                        QontoTransactionRepository txRepo,
                        AccountingEntryRepository entryRepo,
                        AccountingJournalRepository journalRepo,
                        AccountingAccountRepository accountRepo,
                        AccountingService accountingService) {
        this.configRepo = configRepo;
        this.mappingRepo = mappingRepo;
        this.txRepo = txRepo;
        this.entryRepo = entryRepo;
        this.journalRepo = journalRepo;
        this.accountRepo = accountRepo;
        this.accountingService = accountingService;
    }

    public List<QontoConfig> getConfigs(Tenant tenant) {
        return configRepo.findAllByTenantOrderByIdAsc(tenant);
    }

    public boolean isConfigured(Tenant tenant) {
        return configRepo.findAllByTenantOrderByIdAsc(tenant).stream().anyMatch(QontoConfig::isConfigured);
    }

    // ── Sync ─────────────────────────────────────────────────────────────────

    @Transactional
    public SyncResult syncFromQonto(Tenant tenant, int days) {
        QontoConfig config = configRepo.findAllByTenantOrderByIdAsc(tenant).stream()
                .filter(QontoConfig::isConfigured)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Qonto non configuré — renseignez le slug et la clé secrète"));
        return syncFromQontoConfig(tenant, config, days);
    }

    @Transactional
    public SyncResult syncFromQontoConfig(Tenant tenant, QontoConfig config, int days) {
        if (!config.isConfigured()) {
            throw new IllegalStateException("Configuration Qonto incomplète — slug et clé secrète requis");
        }
        LocalDate from = LocalDate.now().minusDays(days);
        LocalDate to   = LocalDate.now();
        List<QontoTransactionDTO> txDtos = fetchAllTransactions(config, from, to);

        int imported = 0, skipped = 0, errors = 0;
        for (QontoTransactionDTO dto : txDtos) {
            QontoImportResult r = processTransaction(tenant, dto, config);
            if (r.success()) imported++;
            else if ("SKIPPED".equals(r.status())) skipped++;
            else errors++;
        }
        return new SyncResult(imported, skipped, errors);
    }

    /** Re-attribute bankAccountNumber on existing transactions by re-fetching IDs from Qonto API per config. */
    @Transactional
    public int reattributeBankAccounts(Tenant tenant, int days) {
        List<QontoConfig> configs = configRepo.findAllByTenantOrderByIdAsc(tenant).stream()
                .filter(QontoConfig::isConfigured)
                .filter(c -> c.getBankAccountNumber() != null && !c.getBankAccountNumber().isBlank())
                .toList();
        int updated = 0;
        LocalDate from = LocalDate.now().minusDays(days);
        LocalDate to   = LocalDate.now();
        for (QontoConfig config : configs) {
            List<QontoTransactionDTO> dtos = fetchAllTransactions(config, from, to);
            for (QontoTransactionDTO dto : dtos) {
                txRepo.findByQontoId(dto.transactionId()).ifPresent(tx -> {
                    tx.setBankAccountNumber(config.getBankAccountNumber());
                    txRepo.save(tx);
                });
            }
            updated += dtos.size();
        }
        return updated;
    }

    // ── Fetch all pages ───────────────────────────────────────────────────────

    private List<QontoTransactionDTO> fetchAllTransactions(QontoConfig config, LocalDate from, LocalDate to) {
        RestClient client = RestClient.builder()
                .baseUrl(QONTO_BASE_URL)
                .defaultHeader("Authorization", config.getOrganizationSlug() + ":" + config.getSecretKey())
                .defaultHeader("Accept", "application/json")
                .build();

        // Qonto API requires iban or bank_account_id — auto-discover if not configured
        List<String> ibans;
        if (config.getIban() != null && !config.getIban().isBlank()) {
            ibans = List.of(config.getIban());
        } else {
            ibans = fetchOrganizationIbans(client, config.getOrganizationSlug());
        }

        String fromStr = from.atStartOfDay().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";
        String toStr   = to.plusDays(1).atStartOfDay().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";

        List<QontoTransactionDTO> all = new ArrayList<>();
        for (String iban : ibans) {
            all.addAll(fetchTransactionsForIban(client, iban, fromStr, toStr));
        }
        return all;
    }

    private List<String> fetchOrganizationIbans(RestClient client, String organizationSlug) {
        try {
            QontoOrganizationDTO.Response resp = client.get()
                    .uri("/organizations/" + organizationSlug)
                    .retrieve()
                    .body(QontoOrganizationDTO.Response.class);
            if (resp == null || resp.organization() == null || resp.organization().bankAccounts() == null) {
                throw new IllegalStateException("Impossible de récupérer les comptes bancaires de l'organisation Qonto");
            }
            List<String> ibans = resp.organization().bankAccounts().stream()
                    .map(QontoOrganizationDTO.BankAccount::iban)
                    .filter(iban -> iban != null && !iban.isBlank())
                    .toList();
            if (ibans.isEmpty()) {
                throw new IllegalStateException("Aucun compte bancaire trouvé dans l'organisation Qonto");
            }
            return ibans;
        } catch (RestClientException e) {
            throw new IllegalStateException("Erreur API Qonto (organisation) : " + e.getMessage(), e);
        }
    }

    private List<QontoTransactionDTO> fetchTransactionsForIban(RestClient client, String iban,
                                                                String from, String to) {
        List<QontoTransactionDTO> all = new ArrayList<>();
        int page = 1;
        int totalPages = 1;
        do {
            try {
                String uri = "/transactions?status[]=completed"
                        + "&iban=" + iban
                        + "&settled_at_from=" + from
                        + "&settled_at_to=" + to
                        + "&current_page=" + page
                        + "&per_page=100";
                QontoPageResponse resp = client.get()
                        .uri(uri)
                        .retrieve()
                        .body(QontoPageResponse.class);
                if (resp == null || resp.transactions() == null) break;
                all.addAll(resp.transactions());
                if (resp.meta() != null) {
                    totalPages = resp.meta().totalPages() != null ? resp.meta().totalPages() : 1;
                }
                page++;
            } catch (RestClientException e) {
                throw new IllegalStateException("Erreur API Qonto : " + e.getMessage(), e);
            }
        } while (page <= totalPages);
        return all;
    }

    // ── Process one transaction ───────────────────────────────────────────────

    @Transactional
    public QontoImportResult processTransaction(Tenant tenant, QontoTransactionDTO dto, QontoConfig config) {
        if (txRepo.existsByQontoId(dto.transactionId())) {
            return new QontoImportResult(false, "SKIPPED", "Déjà importé");
        }

        String configBankNumber = (config != null) ? config.getBankAccountNumber() : null;

        // Les virements internes sont importés mais marqués SKIPPED — ils n'ont pas besoin d'écriture
        if ("internal_transfer".equals(dto.operationType())) {
            saveTx(tenant, dto, "SKIPPED", null, "Virement interne", configBankNumber);
            return new QontoImportResult(false, "SKIPPED", "Virement interne");
        }

        // Toutes les autres transactions sont importées sans écriture automatique — le rapprochement est manuel
        saveTx(tenant, dto, "IMPORTED", null, null, configBankNumber);
        return new QontoImportResult(true, "IMPORTED", dto.transactionId());
    }

    public List<QontoOrganizationDTO.BankAccount> fetchBankAccounts(QontoConfig config) {
        RestClient client = RestClient.builder()
                .baseUrl(QONTO_BASE_URL)
                .defaultHeader("Authorization", config.getOrganizationSlug() + ":" + config.getSecretKey())
                .defaultHeader("Accept", "application/json")
                .build();
        QontoOrganizationDTO.Response resp = client.get()
                .uri("/organizations/" + config.getOrganizationSlug())
                .retrieve()
                .body(QontoOrganizationDTO.Response.class);
        if (resp == null || resp.organization() == null || resp.organization().bankAccounts() == null) {
            throw new IllegalStateException("Impossible de récupérer les comptes bancaires Qonto");
        }
        return resp.organization().bankAccounts();
    }

    private void saveTx(Tenant tenant, QontoTransactionDTO dto, String status, Long entryId, String error, String bankAccountNumber) {
        QontoTransaction tx = new QontoTransaction();
        tx.setTenant(tenant);
        tx.setQontoId(dto.transactionId());
        tx.setOperationType(dto.operationType());
        tx.setSide(dto.side());
        tx.setAmount(BigDecimal.valueOf(dto.amountCents()).divide(BigDecimal.valueOf(100)));
        tx.setCurrency(dto.currency());
        tx.setLabel(dto.label());
        tx.setReference(dto.reference());
        if (dto.settledAt() != null) {
            try { tx.setSettledAt(LocalDate.parse(dto.settledAt().substring(0, 10))); } catch (Exception ignored) {}
        }
        tx.setImportStatus(status);
        tx.setAccountingEntryId(entryId);
        tx.setImportedAt(LocalDateTime.now());
        tx.setErrorMessage(error);
        tx.setBankAccountNumber(bankAccountNumber);
        if (dto.attachmentIds() != null && !dto.attachmentIds().isEmpty()) {
            tx.setAttachmentIds(String.join(",", dto.attachmentIds()));
        }
        txRepo.save(tx);
    }

    // ── Attachments ──────────────────────────────────────────────────────────

    public String fetchAttachmentUrl(QontoConfig config, String attachmentId) {
        RestClient client = RestClient.builder()
                .baseUrl(QONTO_BASE_URL)
                .defaultHeader("Authorization", config.getOrganizationSlug() + ":" + config.getSecretKey())
                .defaultHeader("Accept", "application/json")
                .build();
        try {
            QontoAttachmentResponse resp = client.get()
                    .uri("/attachments/" + attachmentId)
                    .retrieve()
                    .body(QontoAttachmentResponse.class);
            if (resp != null && resp.attachment() != null) {
                return resp.attachment().fileUrl();
            }
        } catch (RestClientException e) {
            throw new IllegalStateException("Erreur API Qonto (attachment) : " + e.getMessage(), e);
        }
        return null;
    }

    public QontoConfig getFirstConfigured(Tenant tenant) {
        return configRepo.findAllByTenantOrderByIdAsc(tenant).stream()
                .filter(QontoConfig::isConfigured)
                .findFirst()
                .orElse(null);
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QontoTransactionDTO(
            @JsonProperty("transaction_id") String transactionId,
            @JsonProperty("amount_cents") long amountCents,
            @JsonProperty("currency") String currency,
            @JsonProperty("side") String side,
            @JsonProperty("operation_type") String operationType,
            @JsonProperty("label") String label,
            @JsonProperty("reference") String reference,
            @JsonProperty("settled_at") String settledAt,
            @JsonProperty("attachment_ids") List<String> attachmentIds
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QontoPageResponse(
            @JsonProperty("transactions") List<QontoTransactionDTO> transactions,
            @JsonProperty("meta") QontoMeta meta
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QontoMeta(
            @JsonProperty("current_page") Integer currentPage,
            @JsonProperty("next_page") Integer nextPage,
            @JsonProperty("total_pages") Integer totalPages,
            @JsonProperty("total_count") Integer totalCount
    ) {}

    public record QontoImportResult(boolean success, String status, String message) {}
    public record SyncResult(int imported, int skipped, int errors) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QontoAttachmentResponse(
            @JsonProperty("attachment") QontoAttachmentData attachment
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QontoAttachmentData(
            @JsonProperty("id") String id,
            @JsonProperty("file_url") String fileUrl,
            @JsonProperty("file_name") String fileName,
            @JsonProperty("file_content_type") String fileContentType
    ) {}
}
