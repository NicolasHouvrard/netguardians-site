package com.logicielpourtous.site.features.supplier;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.logicielpourtous.site.features.connector.PlatformConnector;
import com.logicielpourtous.site.features.connector.PlatformConnectorRepository;
import com.logicielpourtous.site.features.connector.SyncLog;
import com.logicielpourtous.site.features.connector.SyncLogRepository;
import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.product.Product;
import com.logicielpourtous.site.features.product.ProductCategory;
import com.logicielpourtous.site.features.product.ProductCategoryRepository;
import com.logicielpourtous.site.features.product.ProductRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import xiresellers.client.ApiClient;
import xiresellers.client.ApiException;
import xiresellers.client.JSON;
import xiresellers.client.api.AccesstokenApi;
import xiresellers.client.api.ProductCatalogApi;
import xiresellers.client.model.AccesstokenResponse;
import xiresellers.client.model.PriceAndAvailabilityRequest;
import xiresellers.client.model.PriceAndAvailabilityRequestProductsInner;
import xiresellers.client.model.PriceAndAvailabilityResponseInner;
import xiresellers.client.model.ProductSearchResponse;
import xiresellers.client.model.ProductSearchResponseCatalogInner;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Connecteur Ingram Micro utilisant le SDK officiel xi.sdk.resellers.
 *
 * Credentials nécessaires dans le fournisseur (Customer isSupplier=true) :
 *   - supplierApiClientId  → OAuth client_id Ingram
 *   - supplierApiKey       → OAuth client_secret Ingram
 *   - supplierCustomerNumber → IM-CustomerNumber
 *   - supplierCountryCode  → code pays ISO-2 (FR, BE, CH…), défaut "FR"
 *   - supplierApiUrl       → URL de base, défaut : https://api.ingrammicro.com:443
 */
@Service
public class IngramMicroService {

    private static final Logger log = LoggerFactory.getLogger(IngramMicroService.class);

    static final String DEFAULT_BASE_URL  = "https://api.ingrammicro.com:443";
    static final int    PAGE_SIZE         = 100;
    static final int    MAX_PAGES_DEFAULT = 50;  // 5 000 produits max par défaut

    private final java.util.concurrent.atomic.AtomicBoolean syncRunning = new java.util.concurrent.atomic.AtomicBoolean(false);

    // Token refresh threshold: 50 minutes (tokens expire after 60 min)
    private static final long TOKEN_REFRESH_MS = 50L * 60 * 1000;

    private final ProductRepository           productRepository;
    private final ProductCategoryRepository   categoryRepository;
    private final SupplierProductRepository   supplierProductRepository;
    private final CustomerRepository          customerRepository;
    private final IngramSyncTracker           syncTracker;
    private final TransactionTemplate         transactionTemplate;
    private final SyncLogRepository           syncLogRepository;
    private final PlatformConnectorRepository connectorRepository;

    public IngramMicroService(ProductRepository productRepository,
                              ProductCategoryRepository categoryRepository,
                              SupplierProductRepository supplierProductRepository,
                              CustomerRepository customerRepository,
                              IngramSyncTracker syncTracker,
                              PlatformTransactionManager txManager,
                              SyncLogRepository syncLogRepository,
                              PlatformConnectorRepository connectorRepository) {
        this.productRepository         = productRepository;
        this.categoryRepository        = categoryRepository;
        this.supplierProductRepository = supplierProductRepository;
        this.customerRepository        = customerRepository;
        this.syncTracker               = syncTracker;
        this.transactionTemplate       = new TransactionTemplate(txManager);
        this.syncLogRepository         = syncLogRepository;
        this.connectorRepository       = connectorRepository;
    }

    /**
     * Configures the SDK's shared Gson to tolerate unknown JSON fields (e.g. isMsrpVisible,
     * isPriceVisible) that the Ingram sandbox returns but the 1.1.0 SDK model doesn't define.
     * Gson reverses user-registered factories, so our lenient factory is tried first and wraps
     * the SDK's strict CustomTypeAdapterFactory with retry-after-strip logic.
     */
    @PostConstruct
    void configureLenientGson() {
        Gson lenientGson = JSON.createGson()
                .registerTypeAdapterFactory(new LenientUnknownFieldsAdapterFactory())
                .registerTypeAdapter(LocalDate.class, new LocalDateTypeAdapter())
                .create();
        JSON.setGson(lenientGson);
        log.info("Ingram SDK Gson configured: unknown fields ignored, LocalDate adapter registered.");
    }

    private static class LocalDateTypeAdapter extends TypeAdapter<LocalDate> {
        @Override
        public void write(JsonWriter out, LocalDate value) throws IOException {
            if (value == null) out.nullValue();
            else out.value(value.toString());
        }
        @Override
        public LocalDate read(JsonReader in) throws IOException {
            if (in.peek() == com.google.gson.stream.JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return LocalDate.parse(in.nextString());
        }
    }

    private static class LenientUnknownFieldsAdapterFactory implements TypeAdapterFactory {
        @Override
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
            TypeAdapter<T> delegate = gson.getDelegateAdapter(this, type);
            return new TypeAdapter<T>() {
                @Override
                public void write(JsonWriter out, T value) throws IOException {
                    delegate.write(out, value);
                }
                @Override
                public T read(JsonReader in) throws IOException {
                    JsonElement tree = JsonParser.parseReader(in);
                    if (!tree.isJsonObject()) {
                        return delegate.fromJsonTree(tree);
                    }
                    JsonObject obj = tree.getAsJsonObject();
                    while (true) {
                        try {
                            return delegate.fromJsonTree(obj);
                        } catch (IllegalArgumentException e) {
                            // SDK strict adapter message: "The field `fieldName` in the JSON string is not defined..."
                            String msg = e.getMessage();
                            if (msg != null && msg.contains("`")) {
                                int start = msg.indexOf('`') + 1;
                                int end   = msg.indexOf('`', start);
                                if (end > start) {
                                    obj.remove(msg.substring(start, end));
                                    continue;
                                }
                            }
                            throw new JsonParseException("SDK strict validation failed: " + msg, e);
                        }
                    }
                }
            };
        }
    }

    // ── Points d'entrée synchrones ───────────────────────────────────────────

    @Transactional
    public SupplierSyncService.SyncResult syncCatalog(Customer supplier) {
        return syncCatalog(supplier, MAX_PAGES_DEFAULT, null, null);
    }

    @Transactional
    public SupplierSyncService.SyncResult syncCatalog(String clientId, String clientSecret,
                                                       String customerNumber, String countryCode,
                                                       String baseUrl) {
        return syncCatalogInternal(clientId, clientSecret, customerNumber, countryCode,
                baseUrl, MAX_PAGES_DEFAULT, null, null, null);
    }

    @Transactional
    public SupplierSyncService.SyncResult syncCatalog(Customer supplier,
                                                       int maxPages,
                                                       String vendorFilter,
                                                       String keyword) {
        String clientId     = supplier.getSupplierApiClientId();
        String clientSecret = supplier.getSupplierApiKey();
        String custNumber   = supplier.getSupplierCustomerNumber();
        String countryCode  = supplier.getSupplierCountryCode() != null
                              ? supplier.getSupplierCountryCode() : "FR";
        String baseUrl      = (supplier.getSupplierApiUrl() != null
                               && !supplier.getSupplierApiUrl().isBlank())
                              ? supplier.getSupplierApiUrl().trim() : DEFAULT_BASE_URL;

        SupplierSyncService.SyncResult result =
                syncCatalogInternal(clientId, clientSecret, custNumber, countryCode, baseUrl,
                        maxPages, vendorFilter, keyword, supplier);

        supplier.setSupplierLastSyncAt(LocalDateTime.now());
        customerRepository.save(supplier);
        return result;
    }

    // ── Sync complète asynchrone ─────────────────────────────────────────────

    /**
     * Lance la synchronisation complète du catalogue Ingram en arrière-plan.
     * Progresse page par page sans limite, avec refresh du token toutes les 50 min.
     *
     * @param vendors  liste de marques à filtrer (null ou vide = toutes)
     * @param category catégorie Ingram à filtrer (null = toutes)
     * @param keyword  mot-clé produit (null = aucun)
     */
    @Async("syncExecutor")
    public void syncCatalogFullAsync(Long supplierId,
                                     Long connectorId,
                                     List<String> vendors,
                                     String category,
                                     String keyword) {
        List<String> activeVendors = (vendors != null && !vendors.isEmpty()) ? vendors : null;
        String activeCategory      = (category != null && !category.isBlank()) ? category.trim() : null;
        String activeKeyword       = (keyword  != null && !keyword.isBlank())  ? keyword.trim()  : null;

        IngramSyncTracker.JobState state = syncTracker.getOrCreate(supplierId);
        synchronized (state) {
            if (state.status == IngramSyncTracker.SyncStatus.RUNNING) {
                log.warn("Sync Ingram déjà en cours pour fournisseur id={}", supplierId);
                return;
            }
            state.status         = IngramSyncTracker.SyncStatus.RUNNING;
            state.page           = 0;
            state.created        = 0;
            state.updated        = 0;
            state.errors         = 0;
            state.message        = "Démarrage de la synchronisation…";
            state.startedAt      = LocalDateTime.now();
            state.finishedAt     = null;
            state.filterVendors  = activeVendors  != null ? String.join(", ", activeVendors) : null;
            state.filterCategory = activeCategory;
            state.filterKeyword  = activeKeyword;
        }

        // Create SyncLog entry if a connector is linked to this supplier
        Long syncLogId = createSyncLog(supplierId, connectorId, SyncLog.Trigger.MANUAL);

        // Load credentials (just Strings, no JPA session needed after this)
        Customer supplier = customerRepository.findById(supplierId).orElse(null);
        if (supplier == null) {
            state.status  = IngramSyncTracker.SyncStatus.ERROR;
            state.message = "Fournisseur introuvable (id=" + supplierId + ").";
            finalizeSyncLog(syncLogId, SyncLog.Status.ERROR, state);
            return;
        }
        String clientId     = supplier.getSupplierApiClientId();
        String clientSecret = supplier.getSupplierApiKey();
        String custNumber   = supplier.getSupplierCustomerNumber();
        String countryCode  = supplier.getSupplierCountryCode() != null
                              ? supplier.getSupplierCountryCode() : "FR";
        String baseUrl      = (supplier.getSupplierApiUrl() != null
                               && !supplier.getSupplierApiUrl().isBlank())
                              ? supplier.getSupplierApiUrl().trim() : DEFAULT_BASE_URL;
        while (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);

        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            state.status  = IngramSyncTracker.SyncStatus.ERROR;
            state.message = "Credentials Ingram Micro manquants (clientId + clientSecret requis).";
            finalizeSyncLog(syncLogId, SyncLog.Status.ERROR, state);
            return;
        }
        if (custNumber == null || custNumber.isBlank()) {
            state.status  = IngramSyncTracker.SyncStatus.ERROR;
            state.message = "IM-CustomerNumber manquant.";
            finalizeSyncLog(syncLogId, SyncLog.Status.ERROR, state);
            return;
        }

        try {
            // Initial OAuth token
            String[] tokenHolder = { getAccessToken(baseUrl, clientId, clientSecret) };
            long[] tokenObtainedAt = { System.currentTimeMillis() };

            // categoryIdCache: safe to share across pages (stores only IDs)
            Map<String, Long> categoryIdCache = new HashMap<>();

            for (int page = 1; ; page++) {
                // Refresh token before it expires (every 50 min)
                if (System.currentTimeMillis() - tokenObtainedAt[0] > TOKEN_REFRESH_MS) {
                    log.info("Ingram full sync — refresh token à la page {}", page);
                    tokenHolder[0]      = getAccessToken(baseUrl, clientId, clientSecret);
                    tokenObtainedAt[0]  = System.currentTimeMillis();
                }

                final int currentPage = page;
                state.page    = page;
                state.message = String.format("Page %d — %d créés, %d MàJ, %d erreurs",
                        page, state.created, state.updated, state.errors);

                // Fetch page (no transaction needed for API call)
                List<ProductSearchResponseCatalogInner> items;
                try {
                    ApiClient client = buildAuthenticatedClient(baseUrl, tokenHolder[0]);
                    ProductCatalogApi catalogApi = new ProductCatalogApi(client);
                    ProductSearchResponse response = catalogApi.getResellerV6Productsearch(
                            custNumber, UUID.randomUUID().toString().replace("-", ""), countryCode,
                            currentPage, PAGE_SIZE,
                            clientId,       // imSenderID — identifiant app, obligatoire en v6
                            null,           // type
                            null,           // hasDiscounts
                            activeVendors,  // vendor(s)
                            null,           // vendorPartNumber
                            null,           // acceptLanguage
                            null,           // vendorNumber
                            activeKeyword != null ? List.of(activeKeyword) : null, // keyword
                            activeCategory, // category
                            null            // skipAuthorisation
                    );
                    items = response.getCatalog() != null ? response.getCatalog() : List.of();

                    if (items.isEmpty()) {
                        log.info("Ingram full sync — fin à la page {} (réponse vide)", currentPage);
                        break;
                    }

                    Integer recordsFound = response.getRecordsFound();
                    boolean lastPage = (recordsFound != null && currentPage * PAGE_SIZE >= recordsFound)
                            || response.getNextPage() == null;

                    // Save this page in one transaction
                    final List<ProductSearchResponseCatalogInner> pageItems = items;
                    int[] counts = transactionTemplate.execute(status -> {
                        int[] c = {0, 0, 0}; // created, updated, errors
                        // Reload supplier inside the transaction for a managed reference
                        Customer sup = customerRepository.findById(supplierId).orElse(null);
                        if (sup == null) { status.setRollbackOnly(); return c; }
                        for (ProductSearchResponseCatalogInner item : pageItems) {
                            try {
                                boolean wasCreated = upsertProduct(sup, item, categoryIdCache);
                                if (wasCreated) c[0]++; else c[1]++;
                            } catch (Exception e) {
                                log.warn("Erreur upsert produit {} : {}",
                                        item.getIngramPartNumber(), e.getMessage());
                                c[2]++;
                            }
                        }
                        return c;
                    });

                    if (counts != null) {
                        state.created += counts[0];
                        state.updated += counts[1];
                        state.errors  += counts[2];
                    }

                    log.info("Ingram full sync page {} — total: {} créés, {} MàJ, {} erreurs",
                            currentPage, state.created, state.updated, state.errors);

                    // Fetch prices & stock for this page (reuses same catalogApi)
                    final List<String> pageRefs = pageItems.stream()
                            .map(ProductSearchResponseCatalogInner::getIngramPartNumber)
                            .filter(pn -> pn != null && !pn.isBlank())
                            .toList();
                    if (!pageRefs.isEmpty()) {
                        try {
                            Map<String, PriceAndAvailabilityResponseInner> prices =
                                    fetchPricesForPage(catalogApi, custNumber, countryCode, clientId, pageRefs);
                            if (!prices.isEmpty()) {
                                transactionTemplate.execute(txStatus -> {
                                    updatePricingFromResponse(supplierId, pageRefs, prices);
                                    return null;
                                });
                                log.info("Ingram full sync page {} — prix/stock pour {} produits",
                                        currentPage, prices.size());
                            }
                        } catch (ApiException e) {
                            log.warn("Erreur P&A page {} (HTTP {}) : {}", currentPage, e.getCode(), e.getMessage());
                            state.errors++;
                        }
                    }

                    if (lastPage) {
                        log.info("Ingram full sync — dernière page {} atteinte", currentPage);
                        break;
                    }

                } catch (ApiException e) {
                    log.error("Erreur API Ingram page {} : {}", page, e.getMessage());
                    state.errors++;
                    if (e.getCode() == 401 || e.getCode() == 403) {
                        // Token expired mid-sync — try one refresh
                        try {
                            tokenHolder[0]     = getAccessToken(baseUrl, clientId, clientSecret);
                            tokenObtainedAt[0] = System.currentTimeMillis();
                        } catch (ApiException e2) {
                            state.status  = IngramSyncTracker.SyncStatus.ERROR;
                            state.message = "Erreur d'authentification Ingram : " + e2.getMessage();
                            return;
                        }
                    }
                }
            }

            // Update last sync timestamp
            customerRepository.findById(supplierId).ifPresent(sup -> {
                sup.setSupplierLastSyncAt(LocalDateTime.now());
                customerRepository.save(sup);
            });

            state.status      = IngramSyncTracker.SyncStatus.DONE;
            state.finishedAt  = LocalDateTime.now();
            state.message     = String.format("Terminé ! %d références créées, %d mises à jour, %d erreurs.",
                    state.created, state.updated, state.errors);
            finalizeSyncLog(syncLogId, SyncLog.Status.SUCCESS, state);

        } catch (ApiException e) {
            log.error("Erreur OAuth Ingram full sync : {}", e.getMessage());
            state.status  = IngramSyncTracker.SyncStatus.ERROR;
            state.message = "Erreur d'authentification Ingram : " + e.getMessage();
            finalizeSyncLog(syncLogId, SyncLog.Status.ERROR, state);
        }
    }

    // ── Refresh prix & stock (sans reimport catalogue) ───────────────────────

    /**
     * Rafraîchit uniquement les prix d'achat et le stock des produits déjà présents
     * dans le catalogue fournisseur. Beaucoup plus rapide qu'une sync complète.
     */
    @Async("syncExecutor")
    public void refreshPricesAndStockAsync(Long supplierId, Long connectorId) {
        IngramSyncTracker.JobState state = syncTracker.getOrCreate(supplierId);
        synchronized (state) {
            if (state.status == IngramSyncTracker.SyncStatus.RUNNING) {
                log.warn("Sync Ingram déjà en cours pour fournisseur id={}", supplierId);
                return;
            }
            state.status         = IngramSyncTracker.SyncStatus.RUNNING;
            state.page           = 0;
            state.created        = 0;
            state.updated        = 0;
            state.errors         = 0;
            state.message        = "Chargement du catalogue…";
            state.startedAt      = LocalDateTime.now();
            state.finishedAt     = null;
            state.filterVendors  = null;
            state.filterCategory = "mise à jour prix/stock";
            state.filterKeyword  = null;
        }

        Long syncLogId = createSyncLog(supplierId, connectorId, SyncLog.Trigger.MANUAL);

        Customer supplier = customerRepository.findById(supplierId).orElse(null);
        if (supplier == null) {
            state.status  = IngramSyncTracker.SyncStatus.ERROR;
            state.message = "Fournisseur introuvable.";
            finalizeSyncLog(syncLogId, SyncLog.Status.ERROR, state);
            return;
        }
        String clientId     = supplier.getSupplierApiClientId();
        String clientSecret = supplier.getSupplierApiKey();
        String custNumber   = supplier.getSupplierCustomerNumber();
        String countryCode  = supplier.getSupplierCountryCode() != null
                              ? supplier.getSupplierCountryCode() : "FR";
        String baseUrl      = (supplier.getSupplierApiUrl() != null
                               && !supplier.getSupplierApiUrl().isBlank())
                              ? supplier.getSupplierApiUrl().trim() : DEFAULT_BASE_URL;
        while (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);

        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            state.status  = IngramSyncTracker.SyncStatus.ERROR;
            state.message = "Credentials Ingram Micro manquants.";
            finalizeSyncLog(syncLogId, SyncLog.Status.ERROR, state);
            return;
        }

        try {
            // Load existing part numbers (lightweight query — no entity hydration)
            final String finalBaseUrl = baseUrl;
            List<String> partNumbers = transactionTemplate.execute(txStatus ->
                    supplierProductRepository.findSupplierReferencesBySupplierId(supplierId));

            if (partNumbers == null || partNumbers.isEmpty()) {
                state.status    = IngramSyncTracker.SyncStatus.DONE;
                state.finishedAt = LocalDateTime.now();
                state.message   = "Aucun produit à mettre à jour dans le catalogue.";
                finalizeSyncLog(syncLogId, SyncLog.Status.SUCCESS, state);
                return;
            }

            int totalBatches = (partNumbers.size() + 49) / 50;
            state.message = String.format("Mise à jour prix/stock pour %d produits (%d lots de 50)…",
                    partNumbers.size(), totalBatches);

            String[] tokenHolder = { getAccessToken(finalBaseUrl, clientId, clientSecret) };
            long[] tokenObtainedAt = { System.currentTimeMillis() };
            ApiClient[] clientHolder = { buildAuthenticatedClient(finalBaseUrl, tokenHolder[0]) };
            ProductCatalogApi[] apiHolder = { new ProductCatalogApi(clientHolder[0]) };

            int totalUpdated = 0;

            for (int i = 0; i < partNumbers.size(); i += 50) {
                // Refresh token if approaching expiry
                if (System.currentTimeMillis() - tokenObtainedAt[0] > TOKEN_REFRESH_MS) {
                    tokenHolder[0]      = getAccessToken(finalBaseUrl, clientId, clientSecret);
                    tokenObtainedAt[0]  = System.currentTimeMillis();
                    clientHolder[0]     = buildAuthenticatedClient(finalBaseUrl, tokenHolder[0]);
                    apiHolder[0]        = new ProductCatalogApi(clientHolder[0]);
                }

                state.page = i / 50 + 1;
                List<String> chunk = List.copyOf(partNumbers.subList(i, Math.min(i + 50, partNumbers.size())));

                try {
                    Map<String, PriceAndAvailabilityResponseInner> prices =
                            fetchPricesForPage(apiHolder[0], custNumber, countryCode, clientId, chunk);

                    if (!prices.isEmpty()) {
                        transactionTemplate.execute(txStatus -> {
                            updatePricingFromResponse(supplierId, chunk, prices);
                            return null;
                        });
                        totalUpdated += prices.size();
                    }
                } catch (ApiException e) {
                    log.error("Erreur P&A lot {} (HTTP {}) : {} | body: {}",
                            state.page, e.getCode(), e.getMessage(), e.getResponseBody());
                    state.errors++;
                    // Arrêt si erreur d'auth (token invalide ou API non souscrite)
                    if (e.getCode() == 401 || e.getCode() == 403) {
                        state.status  = IngramSyncTracker.SyncStatus.ERROR;
                        state.message = String.format(
                                "Erreur HTTP %d — API Prices & Availability non souscrite ou token invalide. Body: %s",
                                e.getCode(), e.getResponseBody());
                        finalizeSyncLog(syncLogId, SyncLog.Status.ERROR, state);
                        return;
                    }
                }

                state.updated = totalUpdated;
                state.message = String.format("Lot %d/%d — %d mis à jour, %d erreurs",
                        state.page, totalBatches, totalUpdated, state.errors);
            }

            state.status     = IngramSyncTracker.SyncStatus.DONE;
            state.finishedAt = LocalDateTime.now();
            state.message    = String.format("Prix/stock mis à jour : %d produits sur %d.%s",
                    totalUpdated, partNumbers.size(),
                    state.errors > 0 ? " (" + state.errors + " lots en erreur)" : "");
            finalizeSyncLog(syncLogId, SyncLog.Status.SUCCESS, state);

        } catch (ApiException e) {
            log.error("Erreur OAuth refresh prix/stock Ingram : {}", e.getMessage());
            state.status  = IngramSyncTracker.SyncStatus.ERROR;
            state.message = "Erreur authentification : " + e.getMessage();
            finalizeSyncLog(syncLogId, SyncLog.Status.ERROR, state);
        }
    }

    // ── SyncLog helpers ──────────────────────────────────────────────────────

    private Long createSyncLog(Long supplierId, Long connectorId, SyncLog.Trigger trigger) {
        try {
            PlatformConnector connector = connectorId != null
                    ? connectorRepository.findById(connectorId).orElse(null)
                    : connectorRepository.findFirstByCustomerId(supplierId).orElse(null);
            if (connector == null) return null;
            final PlatformConnector conn = connector;
            SyncLog sl = transactionTemplate.execute(txStatus -> {
                SyncLog newLog = new SyncLog();
                newLog.setConnector(conn);
                newLog.setStartedAt(LocalDateTime.now());
                newLog.setTrigger(trigger);
                newLog.setStatus(SyncLog.Status.RUNNING);
                newLog.setMessage("Démarrage…");
                return syncLogRepository.save(newLog);
            });
            return sl != null ? sl.getId() : null;
        } catch (Exception e) {
            log.warn("Impossible de créer le SyncLog pour supplierId={} : {}", supplierId, e.getMessage());
            return null;
        }
    }

    private void finalizeSyncLog(Long logId, SyncLog.Status status, IngramSyncTracker.JobState state) {
        if (logId == null) return;
        try {
            transactionTemplate.execute(txStatus -> {
                syncLogRepository.findById(logId).ifPresent(sl -> {
                    sl.setStatus(status);
                    sl.setFinishedAt(LocalDateTime.now());
                    sl.setCreated(state.created);
                    sl.setUpdated(state.updated);
                    sl.setErrors(state.errors);
                    sl.setMessage(state.message);
                    syncLogRepository.save(sl);
                });
                return null;
            });
        } catch (Exception e) {
            log.warn("Impossible de finaliser le SyncLog id={} : {}", logId, e.getMessage());
        }
    }

    // ── Core interne (sync synchrone) ────────────────────────────────────────

    private SupplierSyncService.SyncResult syncCatalogInternal(String clientId, String clientSecret,
                                                                 String custNumber, String countryCode,
                                                                 String baseUrl, int maxPages,
                                                                 String vendorFilter, String keyword,
                                                                 Customer supplier) {
        if (!syncRunning.compareAndSet(false, true)) {
            log.warn("Sync Ingram Micro déjà en cours — demande ignorée.");
            return new SupplierSyncService.SyncResult(0, 0, 0,
                    "Sync déjà en cours, réessayez dans quelques minutes.");
        }
        try {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            return new SupplierSyncService.SyncResult(0, 0, 0,
                    "Credentials Ingram Micro manquants (clientId + clientSecret requis).");
        }
        if (custNumber == null || custNumber.isBlank()) {
            return new SupplierSyncService.SyncResult(0, 0, 0, "IM-CustomerNumber manquant.");
        }
        if (countryCode == null || countryCode.isBlank()) countryCode = "FR";
        if (baseUrl    == null || baseUrl.isBlank())    baseUrl    = DEFAULT_BASE_URL;
        while (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);

        String bearerToken;
        try {
            bearerToken = getAccessToken(baseUrl, clientId, clientSecret);
        } catch (ApiException e) {
            log.error("Erreur OAuth Ingram : {}", e.getMessage());
            return new SupplierSyncService.SyncResult(0, 0, 1,
                    "Erreur authentification Ingram : " + e.getMessage());
        }

        ApiClient authenticated = buildAuthenticatedClient(baseUrl, bearerToken);
        ProductCatalogApi catalogApi = new ProductCatalogApi(authenticated);

        int created = 0, updated = 0, errors = 0;
        Map<String, Long> categoryIdCache = new HashMap<>();

        for (int page = 1; page <= maxPages; page++) {
            try {
                String correlationId = UUID.randomUUID().toString().replace("-", "");
                ProductSearchResponse response = catalogApi.getResellerV6Productsearch(
                        custNumber, correlationId, countryCode, page, PAGE_SIZE,
                        clientId, // imSenderID — obligatoire v6
                        null, null,
                        vendorFilter != null ? List.of(vendorFilter) : null,
                        null, null, null,
                        keyword != null ? List.of(keyword) : null,
                        null, null
                );

                List<ProductSearchResponseCatalogInner> items =
                        response.getCatalog() != null ? response.getCatalog() : List.of();

                if (items.isEmpty()) break;

                List<String> pageRefs = new java.util.ArrayList<>();
                for (ProductSearchResponseCatalogInner item : items) {
                    try {
                        boolean wasCreated = upsertProduct(supplier, item, categoryIdCache);
                        if (wasCreated) created++; else updated++;
                        if (item.getIngramPartNumber() != null) pageRefs.add(item.getIngramPartNumber());
                    } catch (Exception e) {
                        log.warn("Erreur upsert produit {} : {}", item.getIngramPartNumber(), e.getMessage());
                        errors++;
                    }
                }

                log.info("Ingram page {}/{} — {} produits traités", page, maxPages, items.size());

                if (supplier != null && !pageRefs.isEmpty()) {
                    try {
                        Map<String, PriceAndAvailabilityResponseInner> prices =
                                fetchPricesForPage(catalogApi, custNumber, countryCode, clientId, pageRefs);
                        updatePricingFromResponse(supplier.getId(), pageRefs, prices);
                        log.info("Ingram page {} — prix/stock mis à jour pour {} produits", page, prices.size());
                    } catch (ApiException e) {
                        log.warn("Erreur P&A page {} (HTTP {}) : {}", page, e.getCode(), e.getMessage());
                    }
                }

                Integer recordsFound = response.getRecordsFound();
                if (recordsFound != null && page * PAGE_SIZE >= recordsFound) break;
                if (response.getNextPage() == null) break;

            } catch (ApiException e) {
                log.error("Erreur API Ingram page {} : {}", page, e.getMessage());
                errors++;
                if (e.getCode() == 401 || e.getCode() == 403) break;
            }
        }

        return new SupplierSyncService.SyncResult(created, updated, errors,
                String.format("Ingram Micro : %d créé(s), %d mis à jour, %d erreur(s).",
                        created, updated, errors));
        } finally {
            syncRunning.set(false);
        }
    }

    // ── Upsert d'un produit ───────────────────────────────────────────────────

    private boolean upsertProduct(Customer supplier,
                                  ProductSearchResponseCatalogInner item,
                                  Map<String, Long> categoryIdCache) {
        String ingramRef = item.getIngramPartNumber();
        if (ingramRef == null || ingramRef.isBlank()) return false;

        String description = item.getDescription() != null
                             ? item.getDescription()
                             : (item.getVendorPartNumber() != null
                                ? item.getVendorPartNumber() : ingramRef);

        Product product = productRepository.findBySkuIgnoreCase(ingramRef).orElse(null);
        boolean isNew = (product == null);

        if (isNew) {
            product = new Product();
            product.setSku(ingramRef);
            product.setType(resolveProductType(item.getProductType()));
        }

        product.setName(truncate(description, 255));
        if (item.getExtraDescription() != null && !item.getExtraDescription().isBlank()) {
            product.setDescription(truncate(item.getExtraDescription(), 2000));
        }
        product.setReference(item.getVendorPartNumber());
        product.setActive(true);
        product.setPriceHT(BigDecimal.ZERO);

        // Category — use ID cache to stay transaction-safe
        String catName = item.getCategory();
        if (catName != null && !catName.isBlank()) {
            Long catId = categoryIdCache.computeIfAbsent(catName, this::findOrCreateCategoryId);
            product.setCategory(categoryRepository.findById(catId).orElse(null));
        }

        productRepository.save(product);

        if (supplier != null) {
            SupplierProduct sp = supplierProductRepository
                    .findBySupplierIdAndSupplierReference(supplier.getId(), ingramRef)
                    .orElse(null);
            if (sp == null) {
                sp = new SupplierProduct();
                sp.setSupplier(supplier);
                sp.setProduct(product);
                sp.setSupplierReference(ingramRef);
            }
            sp.setSupplierName(truncate(description, 255));
            sp.setVendorPartNumber(item.getVendorPartNumber());
            sp.setVendorName(item.getVendorName());
            sp.setUpcCode(item.getUpcCode());
            sp.setSubCategory(item.getSubCategory());
            sp.setAvailable("YES".equalsIgnoreCase(item.getAuthorizedToPurchase()));
            sp.setLastSyncAt(LocalDateTime.now());
            supplierProductRepository.save(sp);
        }
        return isNew;
    }

    // ── Pricing & availability ────────────────────────────────────────────────

    /**
     * Fetches customer price and stock for a list of Ingram part numbers (max 50 per call).
     * Throws ApiException on the first HTTP error so the caller can surface it to the user.
     */
    private Map<String, PriceAndAvailabilityResponseInner> fetchPricesForPage(
            ProductCatalogApi catalogApi,
            String custNumber, String countryCode, String clientId,
            List<String> partNumbers) throws ApiException {
        Map<String, PriceAndAvailabilityResponseInner> result = new HashMap<>();
        if (partNumbers.isEmpty()) return result;

        for (int i = 0; i < partNumbers.size(); i += 50) {
            List<String> chunk = partNumbers.subList(i, Math.min(i + 50, partNumbers.size()));
            List<PriceAndAvailabilityRequestProductsInner> requestItems = chunk.stream()
                    .map(pn -> {
                        PriceAndAvailabilityRequestProductsInner item =
                                new PriceAndAvailabilityRequestProductsInner();
                        item.setIngramPartNumber(pn);
                        item.setQuantityRequested("1");
                        return item;
                    }).toList();

            PriceAndAvailabilityRequest request = new PriceAndAvailabilityRequest();
            request.setProducts(requestItems);

            log.info("P&A envoi lot {}/{} — {} refs, exemple: {}",
                    i / 50 + 1, (partNumbers.size() + 49) / 50,
                    chunk.size(), chunk.get(0));

            List<PriceAndAvailabilityResponseInner> resp = catalogApi.postPriceandavailability(
                    false, false,
                    custNumber, UUID.randomUUID().toString().replace("-", ""), countryCode,
                    request, false, clientId);

            log.info("P&A lot {}/{} — {} réponses pour {} références",
                    i / 50 + 1, (partNumbers.size() + 49) / 50,
                    resp != null ? resp.size() : 0, chunk.size());

            if (resp != null) {
                for (PriceAndAvailabilityResponseInner r : resp) {
                    if (r.getIngramPartNumber() != null) {
                        result.put(r.getIngramPartNumber(), r);
                    } else {
                        log.info("P&A item sans ingramPartNumber : statusCode={} statusMsg={} vendor={}",
                                r.getProductStatusCode(), r.getProductStatusMessage(), r.getVendorPartNumber());
                    }
                }
            }
        }
        return result;
    }

    /**
     * Updates purchasePrice and stockQuantity on SupplierProducts.
     * Must be called within an active transaction.
     */
    private void updatePricingFromResponse(Long supplierId, List<String> partNumbers,
                                           Map<String, PriceAndAvailabilityResponseInner> prices) {
        for (String pn : partNumbers) {
            PriceAndAvailabilityResponseInner pr = prices.get(pn);
            if (pr == null) continue;
            supplierProductRepository.findBySupplierIdAndSupplierReference(supplierId, pn)
                    .ifPresent(sp -> {
                        if (pr.getPricing() != null && pr.getPricing().getCustomerPrice() != null) {
                            sp.setPurchasePrice(pr.getPricing().getCustomerPrice());
                        }
                        if (pr.getAvailability() != null
                                && pr.getAvailability().getTotalAvailability() != null) {
                            sp.setStockQuantity(pr.getAvailability().getTotalAvailability());
                        }
                        supplierProductRepository.save(sp);
                    });
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Long findOrCreateCategoryId(String name) {
        return categoryRepository.findByNameIgnoreCase(name)
                .map(ProductCategory::getId)
                .orElseGet(() -> {
                    ProductCategory cat = new ProductCategory();
                    cat.setName(name);
                    return categoryRepository.save(cat).getId();
                });
    }

    /**
     * Extrait la base d'authentification OAuth (scheme://host:port) depuis l'URL API.
     * Exemple : "https://api.ingrammicro.com:443/sandbox" → "https://api.ingrammicro.com:443"
     * Le token endpoint Ingram ne supporte pas le préfixe /sandbox.
     */
    private String extractAuthBaseUrl(String apiUrl) {
        try {
            URI uri = URI.create(apiUrl);
            int port = uri.getPort();
            return uri.getScheme() + "://" + uri.getHost() + (port > 0 ? ":" + port : "");
        } catch (Exception e) {
            log.warn("URL Ingram invalide '{}', utilisation de l'URL par défaut", apiUrl);
            return DEFAULT_BASE_URL;
        }
    }

    private String getAccessToken(String apiBaseUrl, String clientId, String clientSecret)
            throws ApiException {
        String authUrl = extractAuthBaseUrl(apiBaseUrl);
        ApiClient tokenClient = new ApiClient();
        tokenClient.setBasePath(authUrl);
        AccesstokenApi tokenApi = new AccesstokenApi(tokenClient);
        log.info("Ingram OAuth — authUrl={} apiUrl={} clientId={}…",
                authUrl, apiBaseUrl, clientId.substring(0, Math.min(6, clientId.length())));
        AccesstokenResponse resp = tokenApi.getAccesstoken("client_credentials", clientId, clientSecret);
        if (resp == null || resp.getAccessToken() == null) {
            throw new ApiException("Token Ingram null ou vide");
        }
        log.info("Ingram OAuth — token obtenu ({}…)",
                resp.getAccessToken().substring(0, Math.min(20, resp.getAccessToken().length())));
        return resp.getAccessToken();
    }

    private ApiClient buildAuthenticatedClient(String baseUrl, String bearerToken) {
        ApiClient client = new ApiClient();
        client.setBasePath(baseUrl);
        client.addDefaultHeader("Authorization", "Bearer " + bearerToken);
        client.setConnectTimeout(30_000);
        client.setReadTimeout(120_000);
        client.setWriteTimeout(60_000);
        log.info("Ingram catalog client — basePath={}", baseUrl);
        return client;
    }

    private Product.ProductType resolveProductType(String ingramType) {
        if (ingramType == null) return Product.ProductType.PHYSICAL;
        String t = ingramType.toUpperCase();
        if (t.contains("SERV") || t.contains("MAINT")) return Product.ProductType.SERVICE;
        if (t.contains("SOFT") || t.contains("LIC"))   return Product.ProductType.LICENSE;
        return Product.ProductType.PHYSICAL;
    }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }
}
