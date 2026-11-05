package com.logicielpourtous.site.features.purchase;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.File;
import java.io.FileInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;

/**
 * Analyse de factures via Google Document AI Invoice Parser.
 * Utilise l'API REST directement (pas de gRPC) pour éviter les conflits de dépendances.
 * Tarif : 10 $ / 1000 pages (1000 pages/mois gratuites avec l'Invoice Parser).
 */
@Service
public class GoogleDocumentAiService {

    private static final Logger log = LoggerFactory.getLogger(GoogleDocumentAiService.class);
    private static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    @Value("${google.document-ai.project-id:}")
    private String projectId;

    @Value("${google.document-ai.location:eu}")
    private String location;

    @Value("${google.document-ai.processor-id:}")
    private String processorId;

    @Value("${google.document-ai.credentials-path:}")
    private String credentialsPath;

    public boolean isConfigured() {
        return !projectId.isBlank() && !processorId.isBlank()
                && (!credentialsPath.isBlank() || hasApplicationDefault());
    }

    private boolean hasApplicationDefault() {
        try { GoogleCredentials.getApplicationDefault(); return true; } catch (Exception e) { return false; }
    }

    public MindeeService.MindeeResult parseInvoice(File file) throws Exception {
        String token = getAccessToken();

        byte[] fileBytes = Files.readAllBytes(file.toPath());
        String base64Content = Base64.getEncoder().encodeToString(fileBytes);
        String mimeType = file.getName().toLowerCase().endsWith(".pdf") ? "application/pdf" : "image/jpeg";

        String endpoint = String.format(
                "https://%s-documentai.googleapis.com/v1/projects/%s/locations/%s/processors/%s:process",
                location, projectId, location, processorId);

        DocAiResponse resp = RestClient.builder()
                .baseUrl(endpoint)
                .defaultHeader("Authorization", "Bearer " + token)
                .build()
                .post()
                .body(new DocAiRequest(new DocAiRawDocument(base64Content, mimeType)))
                .retrieve()
                .body(DocAiResponse.class);

        if (resp == null || resp.document() == null || resp.document().entities() == null) {
            throw new IllegalStateException("Réponse Google Document AI vide ou sans entités");
        }

        String supplierName = null, invoiceNumber = null, currency = "EUR";
        LocalDate invoiceDate = null, dueDate = null;
        BigDecimal totalTTC = null, totalHT = null, totalTVA = null;

        for (DocAiEntity entity : resp.document().entities()) {
            switch (entity.type() != null ? entity.type() : "") {
                case "supplier_name"    -> supplierName  = trim(entity.mentionText());
                case "invoice_id"       -> invoiceNumber = trim(entity.mentionText());
                case "invoice_date"     -> invoiceDate   = parseDate(entity);
                case "due_date"         -> dueDate       = parseDate(entity);
                case "total_amount"     -> totalTTC      = parseMoney(entity);
                case "net_amount"       -> totalHT       = parseMoney(entity);
                case "total_tax_amount" -> totalTVA      = parseMoney(entity);
                case "currency"         -> { if (entity.mentionText() != null) currency = entity.mentionText().trim(); }
            }
        }

        // Calculs de secours si une valeur manque
        if (totalHT == null && totalTTC != null && totalTVA != null) totalHT = totalTTC.subtract(totalTVA);
        if (totalTVA == null && totalTTC != null && totalHT != null) totalTVA = totalTTC.subtract(totalHT);

        log.info("Google DocAI parsed: vendor={} invoice={} date={} ttc={}", supplierName, invoiceNumber, invoiceDate, totalTTC);
        return new MindeeService.MindeeResult(supplierName, invoiceNumber, invoiceDate, dueDate, totalHT, totalTVA, totalTTC, currency);
    }

    // ── Auth ──────────────────────────────────────────────────────────────────

    private String getAccessToken() throws Exception {
        GoogleCredentials credentials;
        if (credentialsPath != null && !credentialsPath.isBlank()) {
            try (FileInputStream fis = new FileInputStream(credentialsPath)) {
                credentials = ServiceAccountCredentials.fromStream(fis).createScoped(SCOPE);
            }
        } else {
            credentials = GoogleCredentials.getApplicationDefault().createScoped(SCOPE);
        }
        credentials.refreshIfExpired();
        return credentials.getAccessToken().getTokenValue();
    }

    // ── Parsing helpers ───────────────────────────────────────────────────────

    private String trim(String s) { return s != null && !s.isBlank() ? s.trim() : null; }

    private LocalDate parseDate(DocAiEntity entity) {
        try {
            if (entity.normalizedValue() != null && entity.normalizedValue().dateValue() != null) {
                DocAiDateValue dv = entity.normalizedValue().dateValue();
                if (dv.year() != null && dv.year() > 0) {
                    return LocalDate.of(dv.year(), dv.month(), dv.day());
                }
            }
            if (entity.mentionText() != null) {
                return LocalDate.parse(entity.mentionText().trim().substring(0, 10));
            }
        } catch (Exception ignored) {}
        return null;
    }

    private BigDecimal parseMoney(DocAiEntity entity) {
        try {
            if (entity.normalizedValue() != null && entity.normalizedValue().moneyValue() != null) {
                DocAiMoneyValue mv = entity.normalizedValue().moneyValue();
                long units = mv.units() != null ? Long.parseLong(mv.units()) : 0L;
                int nanos  = mv.nanos()  != null ? mv.nanos() : 0;
                return BigDecimal.valueOf(units)
                        .add(BigDecimal.valueOf(nanos).movePointLeft(9))
                        .setScale(2, RoundingMode.HALF_UP);
            }
            // Fallback : parser le texte brut
            if (entity.mentionText() != null) {
                String text = entity.mentionText().replaceAll("[^0-9.,]", "").replace(",", ".");
                if (!text.isBlank()) return new BigDecimal(text).setScale(2, RoundingMode.HALF_UP);
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DocAiRequest(@JsonProperty("rawDocument") DocAiRawDocument rawDocument) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DocAiRawDocument(
            @JsonProperty("content")  String content,
            @JsonProperty("mimeType") String mimeType) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DocAiResponse(@JsonProperty("document") DocAiDocument document) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DocAiDocument(@JsonProperty("entities") List<DocAiEntity> entities) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DocAiEntity(
            @JsonProperty("type")            String                type,
            @JsonProperty("mentionText")     String                mentionText,
            @JsonProperty("normalizedValue") DocAiNormalizedValue  normalizedValue) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DocAiNormalizedValue(
            @JsonProperty("text")       String          text,
            @JsonProperty("moneyValue") DocAiMoneyValue moneyValue,
            @JsonProperty("dateValue")  DocAiDateValue  dateValue) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DocAiMoneyValue(
            @JsonProperty("currencyCode") String  currencyCode,
            @JsonProperty("units")        String  units,
            @JsonProperty("nanos")        Integer nanos) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DocAiDateValue(
            @JsonProperty("year")  Integer year,
            @JsonProperty("month") Integer month,
            @JsonProperty("day")   Integer day) {}
}
