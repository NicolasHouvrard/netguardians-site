package com.logicielpourtous.site.features.accounting;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.invoice.Invoice;
import com.logicielpourtous.site.features.invoice.InvoiceLine;
import com.logicielpourtous.site.features.invoice.InvoiceRepository;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Service d'intégration Qonto PDP pour la facturation électronique B2B.
 * API Qonto PDP : https://api.qonto.com/v2 (même authentification que l'API bancaire)
 */
@Service
public class QontoPdpService {

    private static final String QONTO_API_BASE = "https://api.qonto.com/v2";

    private final QontoConfigRepository configRepo;
    private final InvoiceRepository invoiceRepo;

    public QontoPdpService(QontoConfigRepository configRepo, InvoiceRepository invoiceRepo) {
        this.configRepo = configRepo;
        this.invoiceRepo = invoiceRepo;
    }

    /**
     * Soumet une facture au PDP Qonto pour transmission légale e-invoicing.
     */
    @Transactional
    public PdpResult submitInvoice(Tenant tenant, Invoice invoice) {
        QontoConfig config = configRepo.findAllByTenantOrderByIdAsc(tenant).stream()
                .filter(QontoConfig::isConfigured)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Qonto non configuré pour ce tenant"));

        if (invoice.getStatus() == Invoice.InvoiceStatus.DRAFT) {
            return new PdpResult(false, "La facture doit être finalisée (non brouillon) avant soumission.");
        }
        if (invoice.getEInvoicingStatus() == Invoice.EInvoicingStatus.ACCEPTED) {
            return new PdpResult(false, "Cette facture a déjà été acceptée par le PDP.");
        }
        if (invoice.getCustomer() == null || invoice.getCustomer().getSiret() == null) {
            return new PdpResult(false, "Le client doit avoir un SIRET renseigné pour la facturation électronique.");
        }
        if (tenant.getSiren() == null || tenant.getSiren().isBlank()) {
            return new PdpResult(false, "Votre entreprise doit avoir un SIREN renseigné (Paramètres → Profil).");
        }

        RestClient client = RestClient.builder()
                .baseUrl(QONTO_API_BASE)
                .defaultHeader("Authorization", config.getOrganizationSlug() + ":" + config.getSecretKey())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();

        Map<String, Object> payload = buildPayload(tenant, invoice);

        try {
            PdpSubmitResponse resp = client.post()
                    .uri("/external_invoices")
                    .body(payload)
                    .retrieve()
                    .body(PdpSubmitResponse.class);

            String pdpId = resp != null ? resp.id() : null;
            invoice.setEInvoicingStatus(Invoice.EInvoicingStatus.SUBMITTED);
            invoice.setEInvoicingId(pdpId);
            invoice.setEInvoicingSubmittedAt(LocalDateTime.now());
            invoice.setEInvoicingMessage("Soumise au PDP Qonto" + (pdpId != null ? " — ID: " + pdpId : ""));
            invoiceRepo.save(invoice);
            return new PdpResult(true, "Facture soumise au PDP Qonto avec succès." + (pdpId != null ? " ID: " + pdpId : ""));

        } catch (RestClientResponseException e) {
            String error = "Erreur Qonto PDP " + e.getStatusCode() + " : " + e.getResponseBodyAsString();
            invoice.setEInvoicingStatus(Invoice.EInvoicingStatus.ERROR);
            invoice.setEInvoicingMessage(error);
            invoiceRepo.save(invoice);
            return new PdpResult(false, error);
        } catch (Exception e) {
            String error = "Erreur inattendue : " + e.getMessage();
            invoice.setEInvoicingStatus(Invoice.EInvoicingStatus.ERROR);
            invoice.setEInvoicingMessage(error);
            invoiceRepo.save(invoice);
            return new PdpResult(false, error);
        }
    }

    /**
     * Consulte le statut d'une facture soumise auprès du PDP Qonto.
     */
    @Transactional
    public PdpResult checkStatus(Tenant tenant, Invoice invoice) {
        if (invoice.getEInvoicingId() == null) {
            return new PdpResult(false, "Aucun identifiant PDP — la facture n'a pas encore été soumise.");
        }
        QontoConfig config = configRepo.findAllByTenantOrderByIdAsc(tenant).stream()
                .filter(QontoConfig::isConfigured)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Qonto non configuré"));

        RestClient client = RestClient.builder()
                .baseUrl(QONTO_API_BASE)
                .defaultHeader("Authorization", config.getOrganizationSlug() + ":" + config.getSecretKey())
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();

        try {
            PdpStatusResponse resp = client.get()
                    .uri("/external_invoices/" + invoice.getEInvoicingId())
                    .retrieve()
                    .body(PdpStatusResponse.class);

            if (resp == null) return new PdpResult(false, "Réponse vide du PDP.");

            Invoice.EInvoicingStatus newStatus = switch (resp.status() != null ? resp.status().toLowerCase() : "") {
                case "accepted"  -> Invoice.EInvoicingStatus.ACCEPTED;
                case "rejected"  -> Invoice.EInvoicingStatus.REJECTED;
                case "submitted" -> Invoice.EInvoicingStatus.SUBMITTED;
                default          -> Invoice.EInvoicingStatus.SUBMITTED;
            };

            invoice.setEInvoicingStatus(newStatus);
            invoice.setEInvoicingMessage("Statut PDP : " + resp.status() +
                    (resp.rejectionReason() != null ? " — " + resp.rejectionReason() : ""));
            invoiceRepo.save(invoice);
            return new PdpResult(true, "Statut mis à jour : " + newStatus.label);

        } catch (Exception e) {
            return new PdpResult(false, "Erreur lors de la vérification : " + e.getMessage());
        }
    }

    private Map<String, Object> buildPayload(Tenant tenant, Invoice invoice) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (InvoiceLine line : invoice.getLines()) {
            items.add(Map.of(
                    "description", line.getDescription() != null ? line.getDescription() : "",
                    "quantity",    line.getQuantity() != null ? line.getQuantity() : 1,
                    "unit_price",  line.getUnitPrice() != null ? line.getUnitPrice().setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO,
                    "vat_rate",    line.getVatRate() != null ? line.getVatRate().setScale(2, RoundingMode.HALF_UP) : new BigDecimal("20.00"),
                    "total_excl_tax", line.getTotalHT() != null ? line.getTotalHT().setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO
            ));
        }

        return Map.of(
                "invoice_number",      invoice.getInvoiceNumber(),
                "issue_date",          invoice.getInvoiceDate() != null ? invoice.getInvoiceDate().toString() : "",
                "due_date",            invoice.getDueDate() != null ? invoice.getDueDate().toString() : "",
                "currency",            "EUR",
                "total_excl_tax",      invoice.getTotalHT().setScale(2, RoundingMode.HALF_UP),
                "total_vat",           invoice.getTotalTVA().setScale(2, RoundingMode.HALF_UP),
                "total_incl_tax",      invoice.getTotalTTC().setScale(2, RoundingMode.HALF_UP),
                "supplier", Map.of(
                        "siren",        tenant.getSiren(),
                        "name",         tenant.getName()
                ),
                "customer", Map.of(
                        "siret",        invoice.getCustomer().getSiret() != null ? invoice.getCustomer().getSiret() : "",
                        "name",         invoice.getCustomer().getCompanyName(),
                        "email",        invoice.getCustomer().getEmail() != null ? invoice.getCustomer().getEmail() : ""
                ),
                "lines", items
        );
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PdpSubmitResponse(@JsonProperty("id") String id, @JsonProperty("status") String status) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PdpStatusResponse(
            @JsonProperty("id") String id,
            @JsonProperty("status") String status,
            @JsonProperty("rejection_reason") String rejectionReason
    ) {}

    public record PdpResult(boolean success, String message) {}
}
