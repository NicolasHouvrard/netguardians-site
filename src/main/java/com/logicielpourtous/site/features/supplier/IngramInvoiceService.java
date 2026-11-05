package com.logicielpourtous.site.features.supplier;

import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.order.Order;
import com.logicielpourtous.site.features.order.OrderRepository;
import com.logicielpourtous.site.features.purchase.SupplierInvoice;
import com.logicielpourtous.site.features.purchase.SupplierInvoiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import xiresellers.client.ApiClient;
import xiresellers.client.ApiException;
import xiresellers.client.api.AccesstokenApi;
import xiresellers.client.api.InvoicesApi;
import xiresellers.client.model.AccesstokenResponse;
import xiresellers.client.model.InvoiceDetailsv61Response;
import xiresellers.client.model.InvoiceSearchResponse;
import xiresellers.client.model.InvoiceSearchResponseInvoicesInner;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class IngramInvoiceService {

    private static final Logger log = LoggerFactory.getLogger(IngramInvoiceService.class);

    private final OrderRepository           orderRepository;
    private final CustomerRepository        customerRepository;
    private final SupplierInvoiceRepository supplierInvoiceRepository;

    public IngramInvoiceService(OrderRepository orderRepository,
                                CustomerRepository customerRepository,
                                SupplierInvoiceRepository supplierInvoiceRepository) {
        this.orderRepository           = orderRepository;
        this.customerRepository        = customerRepository;
        this.supplierInvoiceRepository = supplierInvoiceRepository;
    }

    /**
     * Importe les factures Ingram pour une commande donnée.
     * Recherche par customerOrderNumber = order.orderNumber.
     * Crée une SupplierInvoice par facture Ingram si elle n'existe pas encore.
     * @return nombre de factures importées (nouvelles uniquement)
     */
    @Transactional
    public int importInvoicesForOrder(Long orderId, Tenant tenant) throws ApiException {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Commande introuvable : " + orderId));

        if (order.getIngramOrderNumber() == null) {
            throw new IllegalStateException("Cette commande n'a pas encore de numéro Ingram.");
        }

        Customer supplier = resolveSupplier();
        String clientId     = supplier.getSupplierApiClientId();
        String clientSecret = supplier.getSupplierApiKey();
        String custNumber   = supplier.getSupplierCustomerNumber();
        String countryCode  = supplier.getSupplierCountryCode() != null ? supplier.getSupplierCountryCode() : "FR";
        String baseUrl      = resolveBaseUrl(supplier);

        String token = getAccessToken(baseUrl, clientId, clientSecret);
        ApiClient client = buildClient(baseUrl, token);
        InvoicesApi api = new InvoicesApi(client);

        String correlationId = UUID.randomUUID().toString().replace("-", "");

        // Recherche par customerOrderNumber (= notre orderNumber)
        InvoiceSearchResponse searchResp = api.getResellersV6Invoicesearch(
                clientId, custNumber, countryCode, correlationId,
                null, null, null, null, null, null,
                null, null, null, null, null,
                order.getOrderNumber(),  // customerOrderNumber
                null, null, null, null, null, null,
                50, 1, null, null, null);

        if (searchResp == null || searchResp.getInvoices() == null || searchResp.getInvoices().isEmpty()) {
            log.info("Aucune facture Ingram trouvée pour customerOrderNumber={}", order.getOrderNumber());
            return 0;
        }

        int imported = 0;
        for (InvoiceSearchResponseInvoicesInner item : searchResp.getInvoices()) {
            String ingramInvNum = item.getInvoiceNumber();
            if (ingramInvNum == null || ingramInvNum.isBlank()) continue;

            // Éviter les doublons
            if (supplierInvoiceRepository.findByTenantAndIngramInvoiceNumber(tenant, ingramInvNum).isPresent()) {
                log.info("Facture Ingram {} déjà importée — ignorée", ingramInvNum);
                continue;
            }

            // Récupérer le détail pour avoir les montants complets
            InvoiceDetailsv61Response detail = fetchDetails(api, ingramInvNum, custNumber, countryCode, clientId);

            SupplierInvoice inv = new SupplierInvoice();
            inv.setTenant(tenant);
            inv.setVendorName("Ingram Micro");
            inv.setInvoiceNumber(ingramInvNum);
            inv.setIngramInvoiceNumber(ingramInvNum);
            inv.setIngramOrderNumber(order.getIngramOrderNumber());
            inv.setOrderId(orderId);
            inv.setCurrency("EUR");

            // Dates
            inv.setInvoiceDate(parseDate(item.getInvoiceDate()));
            inv.setDueDate(parseDate(item.getInvoiceDueDate()));

            // Montants depuis le détail
            if (detail != null && detail.getSummary() != null && detail.getSummary().getTotals() != null) {
                var totals = detail.getSummary().getTotals();
                if (totals.getNetInvoiceAmount() != null)
                    inv.setTotalHT(BigDecimal.valueOf(totals.getNetInvoiceAmount()));
                if (totals.getTotalTaxAmount() != null)
                    inv.setTotalTVA(BigDecimal.valueOf(totals.getTotalTaxAmount()));
                if (totals.getInvoicedAmountDue() != null)
                    inv.setTotalTTC(BigDecimal.valueOf(totals.getInvoicedAmountDue()));
                else if (inv.getTotalHT() != null && inv.getTotalTVA() != null)
                    inv.setTotalTTC(inv.getTotalHT().add(inv.getTotalTVA()));
            } else if (item.getInvoicedAmountDue() != null) {
                // Fallback : montant de la liste de recherche
                inv.setTotalTTC(item.getInvoicedAmountDue());
            }

            // Statut
            inv.setStatus(mapStatus(item.getInvoiceStatus()));

            // Notes
            if (detail != null && detail.getNotes() != null && !detail.getNotes().isBlank()) {
                inv.setNotes("Ingram : " + detail.getNotes());
            }

            supplierInvoiceRepository.save(inv);
            imported++;
            log.info("Facture Ingram {} importée pour commande {}", ingramInvNum, order.getOrderNumber());
        }

        return imported;
    }

    /**
     * Prévisualise les factures Ingram sans les enregistrer.
     * Retourne les résultats de la recherche brute pour diagnostic.
     */
    public List<InvoiceSearchResponseInvoicesInner> previewInvoicesForOrder(Long orderId) throws ApiException {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Commande introuvable : " + orderId));

        if (order.getIngramOrderNumber() == null) {
            throw new IllegalStateException("Cette commande n'a pas encore de numéro Ingram.");
        }

        Customer supplier = resolveSupplier();
        String clientId    = supplier.getSupplierApiClientId();
        String clientSecret = supplier.getSupplierApiKey();
        String custNumber  = supplier.getSupplierCustomerNumber();
        String countryCode = supplier.getSupplierCountryCode() != null ? supplier.getSupplierCountryCode() : "FR";
        String baseUrl     = resolveBaseUrl(supplier);

        String token = getAccessToken(baseUrl, clientId, clientSecret);
        ApiClient client = buildClient(baseUrl, token);
        InvoicesApi api = new InvoicesApi(client);

        String correlationId = UUID.randomUUID().toString().replace("-", "");

        // Recherche 1 : par customerOrderNumber
        InvoiceSearchResponse resp = api.getResellersV6Invoicesearch(
                clientId, custNumber, countryCode, correlationId,
                null, null, null, null, null, null,
                null, null, null, null, null,
                order.getOrderNumber(), null, null, null, null, null, null,
                50, 1, null, null, null);

        List<InvoiceSearchResponseInvoicesInner> results = new java.util.ArrayList<>();
        if (resp != null && resp.getInvoices() != null) results.addAll(resp.getInvoices());

        // Recherche 2 : par ingramOrderNumber si aucun résultat
        if (results.isEmpty()) {
            correlationId = UUID.randomUUID().toString().replace("-", "");
            resp = api.getResellersV6Invoicesearch(
                    clientId, custNumber, countryCode, correlationId,
                    null, null, null, null, null, null,
                    order.getIngramOrderNumber(), null, null, null, null,
                    null, null, null, null, null, null, null,
                    50, 1, null, null, null);
            if (resp != null && resp.getInvoices() != null) results.addAll(resp.getInvoices());
        }

        return results;
    }

    /**
     * Récupère le détail d'une facture Ingram par son numéro.
     */
    public InvoiceDetailsv61Response fetchInvoiceDetails(String ingramInvoiceNumber) throws ApiException {
        Customer supplier = resolveSupplier();
        String clientId     = supplier.getSupplierApiClientId();
        String clientSecret = supplier.getSupplierApiKey();
        String custNumber   = supplier.getSupplierCustomerNumber();
        String countryCode  = supplier.getSupplierCountryCode() != null ? supplier.getSupplierCountryCode() : "FR";
        String baseUrl      = resolveBaseUrl(supplier);

        String token = getAccessToken(baseUrl, clientId, clientSecret);
        ApiClient client = buildClient(baseUrl, token);
        InvoicesApi api = new InvoicesApi(client);

        return fetchDetails(api, ingramInvoiceNumber, custNumber, countryCode, clientId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private InvoiceDetailsv61Response fetchDetails(InvoicesApi api, String invoiceNumber,
                                                    String custNumber, String countryCode, String clientId)
            throws ApiException {
        String correlationId = UUID.randomUUID().toString().replace("-", "");
        return api.getInvoicedetailsV61(invoiceNumber, custNumber, countryCode, correlationId, clientId, null, false);
    }

    private Customer resolveSupplier() {
        return customerRepository.findSuppliers().stream()
                .filter(s -> s.getSupplierApiClientId() != null && !s.getSupplierApiClientId().isBlank()
                        && s.getSupplierApiKey() != null && !s.getSupplierApiKey().isBlank())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Aucun fournisseur Ingram configuré avec credentials."));
    }

    private String resolveBaseUrl(Customer supplier) {
        String url = supplier.getSupplierApiUrl();
        if (url == null || url.isBlank()) return IngramMicroService.DEFAULT_BASE_URL;
        url = url.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    private String getAccessToken(String baseUrl, String clientId, String clientSecret) throws ApiException {
        String authUrl = extractAuthBaseUrl(baseUrl);
        ApiClient tokenClient = new ApiClient();
        tokenClient.setBasePath(authUrl);
        AccesstokenApi tokenApi = new AccesstokenApi(tokenClient);
        AccesstokenResponse resp = tokenApi.getAccesstoken("client_credentials", clientId, clientSecret);
        if (resp == null || resp.getAccessToken() == null)
            throw new ApiException("Token Ingram null ou vide");
        return resp.getAccessToken();
    }

    private ApiClient buildClient(String baseUrl, String token) {
        ApiClient client = new ApiClient();
        client.setBasePath(baseUrl);
        client.addDefaultHeader("Authorization", "Bearer " + token);
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

    private SupplierInvoice.Status mapStatus(String ingramStatus) {
        if (ingramStatus == null) return SupplierInvoice.Status.DRAFT;
        return switch (ingramStatus.toLowerCase()) {
            case "open"    -> SupplierInvoice.Status.TO_PAY;
            case "paid"    -> SupplierInvoice.Status.PAID;
            case "cancelled", "canceled" -> SupplierInvoice.Status.CANCELLED;
            default        -> SupplierInvoice.Status.DRAFT;
        };
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDate.parse(s.substring(0, 10)); } catch (Exception e) { return null; }
    }
}
