package com.logicielpourtous.site.features.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logicielpourtous.site.features.product.Product;
import com.logicielpourtous.site.features.product.ProductRepository;
import com.logicielpourtous.site.features.shop.ShopProduct;
import com.logicielpourtous.site.features.shop.ShopProductRepository;
import com.logicielpourtous.site.features.supplier.IngramMicroService;
import com.logicielpourtous.site.features.supplier.SupplierProduct;
import com.logicielpourtous.site.features.supplier.SupplierProductRepository;
import com.logicielpourtous.site.features.supplier.SupplierSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
public class ConnectorSyncService {

    private static final Logger log = LoggerFactory.getLogger(ConnectorSyncService.class);

    private final PlatformConnectorRepository connectorRepository;
    private final ProductRepository           productRepository;
    private final SupplierProductRepository   supplierProductRepository;
    private final IngramMicroService          ingramMicroService;
    private final SyncLogRepository           syncLogRepository;
    private final ShopProductRepository       shopProductRepository;
    private final ObjectMapper mapper    = new ObjectMapper();
    private final WebClient    webClient = WebClient.builder().build();

    public ConnectorSyncService(PlatformConnectorRepository connectorRepository,
                                ProductRepository productRepository,
                                SupplierProductRepository supplierProductRepository,
                                IngramMicroService ingramMicroService,
                                SyncLogRepository syncLogRepository,
                                ShopProductRepository shopProductRepository) {
        this.connectorRepository       = connectorRepository;
        this.productRepository         = productRepository;
        this.supplierProductRepository = supplierProductRepository;
        this.ingramMicroService        = ingramMicroService;
        this.syncLogRepository         = syncLogRepository;
        this.shopProductRepository     = shopProductRepository;
    }

    public record SyncResult(int created, int updated, int errors, String message) {}

    /** Point d'entrée avec logging automatique en base (manuel ou planifié). */
    @Transactional
    public SyncResult syncWithLog(Long connectorId, SyncLog.Trigger trigger) {
        PlatformConnector c = connectorRepository.findById(connectorId)
                .orElseThrow(() -> new IllegalArgumentException("Connecteur introuvable"));

        SyncLog logEntry = new SyncLog();
        logEntry.setConnector(c);
        logEntry.setStartedAt(LocalDateTime.now());
        logEntry.setTrigger(trigger);
        logEntry.setStatus(SyncLog.Status.RUNNING);
        syncLogRepository.save(logEntry);

        try {
            SyncResult result = sync(connectorId);
            logEntry.setFinishedAt(LocalDateTime.now());
            logEntry.setCreated(result.created());
            logEntry.setUpdated(result.updated());
            logEntry.setErrors(result.errors());
            logEntry.setMessage(result.message());
            logEntry.setStatus(result.errors() > 0 && result.created() + result.updated() == 0
                    ? SyncLog.Status.ERROR : SyncLog.Status.SUCCESS);
            syncLogRepository.save(logEntry);
            return result;
        } catch (Exception e) {
            logEntry.setFinishedAt(LocalDateTime.now());
            logEntry.setStatus(SyncLog.Status.ERROR);
            logEntry.setMessage("Exception : " + e.getMessage());
            syncLogRepository.save(logEntry);
            throw e;
        }
    }

    @Transactional
    public SyncResult sync(Long connectorId) {
        PlatformConnector c = connectorRepository.findById(connectorId)
                .orElseThrow(() -> new IllegalArgumentException("Connecteur introuvable"));
        if (!c.isActive()) return new SyncResult(0, 0, 0, "Connecteur inactif.");

        // Ingram Micro uses its own SDK-based service — bypass the generic pipeline
        // apiKey=clientId, apiSecret=clientSecret, apiUrl=IM-CustomerNumber (country defaults to FR)
        if (c.getConnectorType() == ConnectorType.INGRAM_MICRO) {
            try {
                // Parse optional "COUNTRYCODE|CUSTOMERNUMBER" format from apiUrl, else treat as customerNumber
                String raw = c.getApiUrl() != null ? c.getApiUrl().trim() : "";
                String customerNumber, countryCode;
                if (raw.contains("|")) {
                    String[] parts = raw.split("\\|", 2);
                    countryCode    = parts[0].trim();
                    customerNumber = parts[1].trim();
                } else {
                    countryCode    = "FR";
                    customerNumber = raw;
                }
                SupplierSyncService.SyncResult r = ingramMicroService.syncCatalog(
                        c.getApiKey(), c.getApiSecret(), customerNumber, countryCode, null);
                c.setLastSyncAt(java.time.LocalDateTime.now());
                c.setLastSyncCount(r.created() + r.updated());
                c.setLastSyncStatus(r.message());
                connectorRepository.save(c);
                return new SyncResult(r.created(), r.updated(), r.errors(), r.message());
            } catch (Exception e) {
                String msg = "Erreur Ingram Micro : " + e.getMessage();
                c.setLastSyncAt(java.time.LocalDateTime.now());
                c.setLastSyncStatus(msg);
                connectorRepository.save(c);
                log.error("Sync Ingram échoué connecteur id={}: {}", connectorId, e.getMessage());
                return new SyncResult(0, 0, 1, msg);
            }
        }

        try {
            List<RemoteProduct> remotes = switch (c.getConnectorType()) {
                case WOOCOMMERCE  -> fetchWooCommerce(c);
                case PRESTASHOP   -> fetchPrestaShop(c);
                case SHOPIFY      -> fetchShopify(c);
                case INGRAM_MICRO -> List.of(); // handled above
                case TECHDATA     -> fetchTechDataProducts(c);
                case CUSTOM_REST  -> fetchCustomRest(c);
            };

            int created = 0, updated = 0, errors = 0;
            for (RemoteProduct rp : remotes) {
                try {
                    var result = upsertSupplierProduct(c, rp);
                    if (result == UpsertResult.CREATED) created++;
                    else updated++;
                } catch (Exception e) {
                    errors++;
                    log.warn("Erreur sync produit SKU={}: {}", rp.sku(), e.getMessage());
                }
            }

            c.setLastSyncAt(LocalDateTime.now());
            c.setLastSyncCount(created + updated);
            c.setLastSyncStatus("OK — " + created + " créés, " + updated + " mis à jour, " + errors + " erreurs");
            connectorRepository.save(c);

            return new SyncResult(created, updated, errors, c.getLastSyncStatus());

        } catch (Exception e) {
            String msg = "Erreur sync : " + e.getMessage();
            c.setLastSyncAt(LocalDateTime.now());
            c.setLastSyncStatus(msg);
            connectorRepository.save(c);
            log.error("Sync échoué connecteur id={}: {}", connectorId, e.getMessage());
            return new SyncResult(0, 0, 1, msg);
        }
    }

    // ── Fetch par type ────────────────────────────────────────────────────────

    private List<RemoteProduct> fetchWooCommerce(PlatformConnector c) throws Exception {
        // Basic auth: consumer_key:consumer_secret, paginate via ?page=N&per_page=100
        String credentials = c.getApiKey() + ":" + c.getApiSecret();
        String auth = "Basic " + Base64.getEncoder().encodeToString(
                credentials.getBytes(StandardCharsets.UTF_8));

        String url = c.getApiUrl();
        if (!url.contains("/wp-json/wc/v3/products")) url = url.replaceAll("/$", "") + "/wp-json/wc/v3/products";

        String json = webClient.get()
                .uri(url + "?per_page=100&status=publish")
                .header("Authorization", auth)
                .retrieve().bodyToMono(String.class).block();

        return parseWooCommerce(json);
    }

    private List<RemoteProduct> parseWooCommerce(String json) throws Exception {
        List<RemoteProduct> result = new ArrayList<>();
        JsonNode arr = mapper.readTree(json);
        for (JsonNode item : arr) {
            String sku   = item.path("sku").asText(null);
            String name  = item.path("name").asText("");
            String price = item.path("regular_price").asText(null);
            int stock    = item.path("stock_quantity").asInt(0);
            if (sku != null && !sku.isBlank() && price != null && !price.isBlank()) {
                result.add(new RemoteProduct(sku, name, new BigDecimal(price), stock));
            }
        }
        return result;
    }

    private List<RemoteProduct> fetchPrestaShop(PlatformConnector c) throws Exception {
        // PrestaShop WebService: Basic auth with API key + blank password, JSON output
        String auth = "Basic " + Base64.getEncoder().encodeToString(
                (c.getApiKey() + ":").getBytes(StandardCharsets.UTF_8));
        String url = c.getApiUrl().replaceAll("/$", "") + "/api/products?output_format=JSON&display=full";

        String json = webClient.get()
                .uri(url)
                .header("Authorization", auth)
                .retrieve().bodyToMono(String.class).block();

        return parsePrestaShop(json);
    }

    private List<RemoteProduct> parsePrestaShop(String json) throws Exception {
        List<RemoteProduct> result = new ArrayList<>();
        JsonNode root = mapper.readTree(json);
        JsonNode products = root.path("products");
        for (JsonNode item : products) {
            String ref   = item.path("reference").asText(null);
            String name  = item.path("name").asText("");
            String price = item.path("price").asText(null);
            int qty      = item.path("quantity").asInt(0);
            if (ref != null && !ref.isBlank() && price != null && !price.isBlank()) {
                result.add(new RemoteProduct(ref, name, new BigDecimal(price), qty));
            }
        }
        return result;
    }

    private List<RemoteProduct> fetchShopify(PlatformConnector c) throws Exception {
        // Shopify Admin API: X-Shopify-Access-Token header
        String url = c.getApiUrl().replaceAll("/$", "") + "/admin/api/2024-01/products.json?limit=250&status=active";

        String json = webClient.get()
                .uri(url)
                .header("X-Shopify-Access-Token", c.getApiKey())
                .retrieve().bodyToMono(String.class).block();

        return parseShopify(json);
    }

    private List<RemoteProduct> parseShopify(String json) throws Exception {
        List<RemoteProduct> result = new ArrayList<>();
        JsonNode root = mapper.readTree(json);
        for (JsonNode product : root.path("products")) {
            String title = product.path("title").asText("");
            for (JsonNode variant : product.path("variants")) {
                String sku   = variant.path("sku").asText(null);
                String price = variant.path("price").asText(null);
                int qty      = variant.path("inventory_quantity").asInt(0);
                if (sku != null && !sku.isBlank() && price != null && !price.isBlank()) {
                    result.add(new RemoteProduct(sku, title, new BigDecimal(price), qty));
                }
            }
        }
        return result;
    }

    private List<RemoteProduct> fetchTechDataProducts(PlatformConnector c) throws Exception {
        log.info("Tech Data sync — implémentation à compléter");
        return List.of();
    }

    private List<RemoteProduct> fetchTechData(PlatformConnector c) throws Exception {
        log.info("Tech Data sync — implémentation à compléter");
        return List.of();
    }

    private List<RemoteProduct> fetchCustomRest(PlatformConnector c) throws Exception {
        // Generic REST: GET apiUrl, bearer token in apiKey
        // Expected response: [{"sku":"X","name":"Y","price":9.99,"stock":10}, ...]
        WebClient.RequestHeadersSpec<?> req = webClient.get().uri(c.getApiUrl());
        if (c.getApiKey() != null && !c.getApiKey().isBlank()) {
            req = req.header("Authorization", "Bearer " + c.getApiKey());
        }
        String json = req.retrieve().bodyToMono(String.class).block();
        return parseCustomRest(json);
    }

    private List<RemoteProduct> parseCustomRest(String json) throws Exception {
        List<RemoteProduct> result = new ArrayList<>();
        JsonNode arr = mapper.readTree(json);
        for (JsonNode item : arr) {
            String sku   = item.path("sku").asText(item.path("reference").asText(null));
            String name  = item.path("name").asText(item.path("title").asText(""));
            double price = item.path("price").asDouble(item.path("priceHT").asDouble(-1));
            int qty      = item.path("stock").asInt(item.path("quantity").asInt(0));
            if (sku != null && !sku.isBlank() && price >= 0) {
                result.add(new RemoteProduct(sku, name, BigDecimal.valueOf(price), qty));
            }
        }
        return result;
    }

    // ── Upsert ────────────────────────────────────────────────────────────────

    private enum UpsertResult { CREATED, UPDATED }

    private UpsertResult upsertSupplierProduct(PlatformConnector c, RemoteProduct rp) {
        // Find matching product by SKU or reference
        Product product = productRepository.findBySkuIgnoreCase(rp.sku())
                .or(() -> productRepository.findByReferenceIgnoreCase(rp.sku()))
                .orElse(null);
        if (product == null) return UpsertResult.UPDATED; // skip unknown products

        // Find or create SupplierProduct
        SupplierProduct sp = (c.getCustomer() != null)
                ? supplierProductRepository.findByProductIdOrderBySupplierId(product.getId()).stream()
                        .filter(s -> c.getCustomer().getId().equals(s.getSupplier().getId()))
                        .findFirst().orElse(null)
                : null;

        boolean isNew = (sp == null);
        if (isNew) {
            sp = new SupplierProduct();
            sp.setProduct(product);
            if (c.getCustomer() != null) sp.setSupplier(c.getCustomer());
        }

        sp.setPurchasePrice(rp.price());
        sp.setStockQuantity(rp.stock());
        sp.setSupplierName(rp.name());
        sp.setSupplierReference(rp.sku());
        sp.setLastSyncAt(LocalDateTime.now());
        supplierProductRepository.save(sp);

        // Auto-publication en boutique
        if (c.isPublishToBoutique()) {
            ShopProduct shopProduct = shopProductRepository.findByProductId(product.getId())
                    .orElseGet(() -> {
                        ShopProduct s = new ShopProduct();
                        s.setProduct(product);
                        s.setSlug(uniqueSlug(product.getName()));
                        return s;
                    });
            shopProduct.setPublished(true);
            if (c.getTenantId() != null && !c.getTenantId().isBlank())
                shopProduct.setBrand(c.getTenantId());
            shopProductRepository.save(shopProduct);
        }

        return isNew ? UpsertResult.CREATED : UpsertResult.UPDATED;
    }

    private String uniqueSlug(String base) {
        String slug = base.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        String candidate = slug;
        int suffix = 2;
        while (shopProductRepository.findBySlug(candidate).isPresent())
            candidate = slug + "-" + suffix++;
        return candidate;
    }

    // ── DTO interne ───────────────────────────────────────────────────────────

    private record RemoteProduct(String sku, String name, BigDecimal price, int stock) {}
}
