package com.logicielpourtous.site.features.supplier;

import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.order.Order;
import com.logicielpourtous.site.features.order.OrderLine;
import com.logicielpourtous.site.features.order.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import xiresellers.client.ApiClient;
import xiresellers.client.ApiException;
import xiresellers.client.api.AccesstokenApi;
import xiresellers.client.api.OrdersApi;
import xiresellers.client.model.OrderDetailB2B;
import xiresellers.client.model.AccesstokenResponse;
import xiresellers.client.model.OrderCreateRequest;
import xiresellers.client.model.OrderCreateRequestLinesInner;
import xiresellers.client.model.OrderCreateRequestShipToInfo;
import xiresellers.client.model.OrderCreateResponse;
import xiresellers.client.model.OrderCreateResponseOrdersInner;
import xiresellers.client.model.OrderSearchResponse;
import xiresellers.client.model.OrderSearchResponseOrdersInner;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class IngramOrderService {

    private static final Logger log = LoggerFactory.getLogger(IngramOrderService.class);

    private final OrderRepository            orderRepository;
    private final SupplierProductRepository  supplierProductRepository;
    private final CustomerRepository         customerRepository;
    private final TransactionTemplate        transactionTemplate;

    public IngramOrderService(OrderRepository orderRepository,
                              SupplierProductRepository supplierProductRepository,
                              CustomerRepository customerRepository,
                              PlatformTransactionManager txManager) {
        this.orderRepository           = orderRepository;
        this.supplierProductRepository = supplierProductRepository;
        this.customerRepository        = customerRepository;
        this.transactionTemplate       = new TransactionTemplate(txManager);
    }

    /**
     * Passe automatiquement la commande chez Ingram Micro en arrière-plan.
     * Appelé lors du passage au statut CONFIRMED.
     * Les lignes sans référence Ingram (prestations, licences…) sont ignorées silencieusement.
     */
    @Async("syncExecutor")
    public void placeOrderAsync(Long orderId) {
        log.info("Passage commande Ingram — orderId={}", orderId);

        // Chargement hors transaction (données simples)
        Order order = transactionTemplate.execute(tx -> {
            Order o = orderRepository.findById(orderId).orElse(null);
            if (o != null) {
                o.getLines().size();
                if (o.getCustomer() != null) o.getCustomer().getCompanyName();
            }
            return o;
        });

        if (order == null) {
            log.warn("Commande {} introuvable pour passage Ingram", orderId);
            return;
        }

        // Fournisseur Ingram : premier supplier avec credentials configurés
        Customer ingramSupplier = customerRepository.findSuppliers().stream()
                .filter(s -> s.getSupplierApiClientId() != null && !s.getSupplierApiClientId().isBlank()
                        && s.getSupplierApiKey() != null && !s.getSupplierApiKey().isBlank())
                .findFirst().orElse(null);

        if (ingramSupplier == null) {
            saveError(orderId, "Aucun fournisseur Ingram Micro configuré avec des credentials valides.");
            return;
        }

        String clientId    = ingramSupplier.getSupplierApiClientId();
        String clientSecret = ingramSupplier.getSupplierApiKey();
        String custNumber  = ingramSupplier.getSupplierCustomerNumber();
        String countryCode = ingramSupplier.getSupplierCountryCode() != null
                             ? ingramSupplier.getSupplierCountryCode() : "FR";
        String baseUrl     = (ingramSupplier.getSupplierApiUrl() != null
                              && !ingramSupplier.getSupplierApiUrl().isBlank())
                             ? ingramSupplier.getSupplierApiUrl().trim() : IngramMicroService.DEFAULT_BASE_URL;
        while (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);

        // Construction des lignes Ingram (uniquement les produits avec une référence Ingram)
        Long supplierId = ingramSupplier.getId();
        List<OrderCreateRequestLinesInner> ingramLines = new ArrayList<>();
        int lineNum = 1;
        for (OrderLine ol : order.getLines()) {
            if (ol.getProduct() == null) continue;
            Optional<SupplierProduct> sp = supplierProductRepository
                    .findBySupplierIdAndProductId(supplierId, ol.getProduct().getId());
            if (sp.isEmpty() || sp.get().getSupplierReference() == null
                    || sp.get().getSupplierReference().isBlank()) continue;

            OrderCreateRequestLinesInner line = new OrderCreateRequestLinesInner();
            line.setCustomerLineNumber(String.valueOf(lineNum++));
            line.setIngramPartNumber(sp.get().getSupplierReference());
            line.setQuantity(ol.getQuantity());
            if (sp.get().getPurchasePrice() != null) {
                line.setUnitPrice(sp.get().getPurchasePrice());
            }
            ingramLines.add(line);
        }

        if (ingramLines.isEmpty()) {
            log.info("Aucune ligne produit Ingram dans la commande {} — passage Ingram ignoré", orderId);
            saveError(orderId, "Aucun produit Ingram trouvé dans les lignes de la commande.");
            return;
        }

        // Construction de la requête
        OrderCreateRequest req = new OrderCreateRequest();
        req.setCustomerOrderNumber(order.getOrderNumber());
        req.setAcceptBackOrder(true);
        if (order.getNotes() != null && !order.getNotes().isBlank()) {
            req.setNotes(order.getNotes().length() > 200
                    ? order.getNotes().substring(0, 200) : order.getNotes());
        }

        // Adresse de livraison : chez le client (drop-shipping) OU chez le revendeur (adresse Ingram par défaut)
        boolean deliverToCustomer = order.getDeliveryMode() == null
                || order.getDeliveryMode() == Order.DeliveryMode.CUSTOMER;
        if (deliverToCustomer) {
            // Validation des champs obligatoires Ingram
            if (isBlank(order.getDeliveryAddress1()) || isBlank(order.getDeliveryCity())
                    || isBlank(order.getDeliveryPostalCode())) {
                saveError(orderId, "Adresse de livraison incomplète. Renseignez la rue, la ville et le code postal "
                        + "dans la section \"Adresse de livraison\" avant de commander chez Ingram.");
                return;
            }
            OrderCreateRequestShipToInfo shipTo = new OrderCreateRequestShipToInfo();
            String company = !isBlank(order.getDeliveryCompany())
                    ? order.getDeliveryCompany()
                    : (order.getCustomer() != null ? order.getCustomer().getCompanyName() : "");
            shipTo.setCompanyName(company);
            shipTo.setName1(company);
            if (!isBlank(order.getDeliveryContact())) shipTo.setContact(order.getDeliveryContact());
            shipTo.setAddressLine1(order.getDeliveryAddress1());
            if (!isBlank(order.getDeliveryAddress2())) shipTo.setAddressLine2(order.getDeliveryAddress2());
            shipTo.setCity(order.getDeliveryCity());
            shipTo.setPostalCode(order.getDeliveryPostalCode());
            shipTo.setCountryCode(!isBlank(order.getDeliveryCountry()) ? order.getDeliveryCountry() : "FR");
            if (!isBlank(order.getDeliveryPhone())) shipTo.setPhoneNumber(order.getDeliveryPhone());
            req.setShipToInfo(shipTo);
            log.info("Commande Ingram {} — livraison drop-shipping : {} {}", orderId, company, order.getDeliveryCity());
        } else {
            log.info("Commande Ingram {} — livraison à l'adresse revendeur Ingram (par défaut)", orderId);
        }

        req.setLines(ingramLines);

        // Appel API Ingram
        try {
            String bearerToken = getAccessToken(baseUrl, clientId, clientSecret);
            ApiClient client = buildAuthenticatedClient(baseUrl, bearerToken);
            OrdersApi ordersApi = new OrdersApi(client);

            String correlationId = UUID.randomUUID().toString().replace("-", "");
            OrderCreateResponse response = ordersApi.postCreateorderV6(
                    custNumber, countryCode, correlationId, req, clientId);

            // Extraction du numéro de commande Ingram
            String ingramOrderNumber = null;
            if (response != null && response.getOrders() != null) {
                ingramOrderNumber = response.getOrders().stream()
                        .map(OrderCreateResponseOrdersInner::getIngramOrderNumber)
                        .filter(n -> n != null && !n.isBlank())
                        .findFirst().orElse(null);
            }

            if (ingramOrderNumber != null) {
                final String finalNumber = ingramOrderNumber;
                transactionTemplate.execute(tx -> {
                    orderRepository.findById(orderId).ifPresent(o -> {
                        o.setIngramOrderNumber(finalNumber);
                        o.setIngramOrderedAt(LocalDateTime.now());
                        o.setIngramOrderError(null);
                        orderRepository.save(o);
                    });
                    return null;
                });
                log.info("Commande Ingram {} créée pour notre commande id={}", ingramOrderNumber, orderId);
            } else {
                saveError(orderId, "Ingram n'a pas retourné de numéro de commande. Réponse : "
                        + (response != null ? response.toString() : "null"));
            }

        } catch (ApiException e) {
            log.error("Erreur API Ingram commande id={}: HTTP {} — {}", orderId, e.getCode(), e.getResponseBody());
            // CUSTOMER-PO-EXISTS : la commande existe déjà chez Ingram (ex. timeout lors d'un précédent envoi)
            // On tente de la retrouver via l'API de recherche
            if (e.getCode() == 400 && e.getResponseBody() != null
                    && e.getResponseBody().contains("CUSTOMER-PO-EXISTS")) {
                log.info("CUSTOMER-PO-EXISTS pour orderId={} — recherche de la commande existante chez Ingram", orderId);
                try {
                    String found = findExistingIngramOrder(
                            order.getOrderNumber(), buildAuthenticatedClient(baseUrl, getAccessToken(baseUrl, clientId, clientSecret)),
                            custNumber, countryCode, clientId);
                    if (found != null) {
                        final String fn = found;
                        transactionTemplate.execute(tx -> {
                            orderRepository.findById(orderId).ifPresent(o -> {
                                o.setIngramOrderNumber(fn);
                                o.setIngramOrderedAt(LocalDateTime.now());
                                o.setIngramOrderError(null);
                                orderRepository.save(o);
                            });
                            return null;
                        });
                        log.info("Commande Ingram retrouvée : {} pour orderId={}", found, orderId);
                        return;
                    }
                } catch (Exception ex) {
                    log.warn("Impossible de retrouver la commande Ingram existante : {}", ex.getMessage());
                }
                saveError(orderId, "Commande déjà enregistrée chez Ingram (CUSTOMER-PO-EXISTS) "
                        + "mais numéro non récupéré automatiquement. Vérifiez sur le portail Ingram.");
            } else {
                saveError(orderId, "Erreur HTTP " + e.getCode() + " : " + e.getMessage());
            }
        }
    }

    /**
     * Récupère les détails complets d'une commande Ingram (frais de port, lignes, statut…).
     * Appel synchrone — à utiliser depuis un contrôleur, pas depuis un thread async.
     */
    public OrderDetailB2B fetchOrderDetails(Long orderId) throws ApiException {
        Order order = transactionTemplate.execute(tx ->
                orderRepository.findById(orderId).orElse(null));
        if (order == null || order.getIngramOrderNumber() == null) {
            throw new IllegalArgumentException("Commande sans numéro Ingram");
        }

        Customer ingramSupplier = customerRepository.findSuppliers().stream()
                .filter(s -> s.getSupplierApiClientId() != null && !s.getSupplierApiClientId().isBlank()
                        && s.getSupplierApiKey() != null && !s.getSupplierApiKey().isBlank())
                .findFirst().orElseThrow(() -> new IllegalStateException("Aucun fournisseur Ingram configuré"));

        String clientId    = ingramSupplier.getSupplierApiClientId();
        String clientSecret = ingramSupplier.getSupplierApiKey();
        String custNumber  = ingramSupplier.getSupplierCustomerNumber();
        String countryCode = ingramSupplier.getSupplierCountryCode() != null
                             ? ingramSupplier.getSupplierCountryCode() : "FR";
        String baseUrl     = (ingramSupplier.getSupplierApiUrl() != null
                              && !ingramSupplier.getSupplierApiUrl().isBlank())
                             ? ingramSupplier.getSupplierApiUrl().trim() : IngramMicroService.DEFAULT_BASE_URL;
        while (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);

        String token = getAccessToken(baseUrl, clientId, clientSecret);
        ApiClient client = buildAuthenticatedClient(baseUrl, token);
        OrdersApi api = new OrdersApi(client);

        String correlationId = UUID.randomUUID().toString().replace("-", "");
        return api.getOrderdetailsV61(
                order.getIngramOrderNumber(),
                custNumber, countryCode, correlationId,
                clientId,
                null, null, null, null, null);
    }

    private String findExistingIngramOrder(String customerOrderNumber, ApiClient client,
                                            String custNumber, String countryCode, String clientId)
            throws ApiException {
        OrdersApi api = new OrdersApi(client);
        String correlationId = UUID.randomUUID().toString().replace("-", "");
        OrderSearchResponse resp = api.getResellersV6Ordersearch(
                custNumber, countryCode, correlationId,
                null, null, null, null, null,
                clientId,
                customerOrderNumber,   // customerOrderNumber — critère de recherche
                10, 1,
                null, null, null, null, null, null, null, null, null, null);
        if (resp != null && resp.getOrders() != null) {
            return resp.getOrders().stream()
                    .map(OrderSearchResponseOrdersInner::getIngramOrderNumber)
                    .filter(n -> n != null && !n.isBlank())
                    .findFirst().orElse(null);
        }
        return null;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private void saveError(Long orderId, String error) {
        transactionTemplate.execute(tx -> {
            orderRepository.findById(orderId).ifPresent(o -> {
                o.setIngramOrderError(error);
                o.setIngramOrderedAt(LocalDateTime.now());
                orderRepository.save(o);
            });
            return null;
        });
        log.warn("Ingram order error orderId={}: {}", orderId, error);
    }

    private String getAccessToken(String apiBaseUrl, String clientId, String clientSecret)
            throws ApiException {
        String authUrl = extractAuthBaseUrl(apiBaseUrl);
        ApiClient tokenClient = new ApiClient();
        tokenClient.setBasePath(authUrl);
        AccesstokenApi tokenApi = new AccesstokenApi(tokenClient);
        AccesstokenResponse resp = tokenApi.getAccesstoken("client_credentials", clientId, clientSecret);
        if (resp == null || resp.getAccessToken() == null) {
            throw new ApiException("Token Ingram null ou vide");
        }
        return resp.getAccessToken();
    }

    private ApiClient buildAuthenticatedClient(String baseUrl, String bearerToken) {
        ApiClient client = new ApiClient();
        client.setBasePath(baseUrl);
        client.addDefaultHeader("Authorization", "Bearer " + bearerToken);
        client.setConnectTimeout(30_000);
        client.setReadTimeout(60_000);
        client.setWriteTimeout(60_000);
        return client;
    }

    private String extractAuthBaseUrl(String apiUrl) {
        try {
            URI uri = URI.create(apiUrl);
            int port = uri.getPort();
            return uri.getScheme() + "://" + uri.getHost() + (port > 0 ? ":" + port : "");
        } catch (Exception e) {
            return IngramMicroService.DEFAULT_BASE_URL;
        }
    }
}
