package com.logicielpourtous.site.features.purchase;

import com.mindee.MindeeClient;
import com.mindee.input.LocalInputSource;
import com.mindee.parsing.common.PredictResponse;
import com.mindee.product.invoice.InvoiceV4;
import com.mindee.product.invoice.InvoiceV4Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.math.BigDecimal;
import java.time.LocalDate;

@Service
public class MindeeService {

    private static final Logger log = LoggerFactory.getLogger(MindeeService.class);

    @Value("${mindee.api-key:}")
    private String apiKey;

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public MindeeResult parseInvoice(File file) throws Exception {
        if (!isConfigured()) throw new IllegalStateException("Clé API Mindee non configurée (mindee.api-key)");

        MindeeClient client = new MindeeClient(apiKey);
        LocalInputSource source = new LocalInputSource(file);
        PredictResponse<InvoiceV4> response = client.parse(InvoiceV4.class, source);
        InvoiceV4Document prediction = response.getDocument().getInference().getPrediction();

        String supplierName  = safeStr(prediction.getSupplierName() != null ? prediction.getSupplierName().getValue() : null);
        String invoiceNumber = safeStr(prediction.getInvoiceNumber() != null ? prediction.getInvoiceNumber().getValue() : null);
        LocalDate invoiceDate = prediction.getInvoiceDateField() != null ? prediction.getInvoiceDateField().getValue() : null;
        LocalDate dueDate     = prediction.getDueDateField() != null ? prediction.getDueDateField().getValue() : null;
        BigDecimal totalTTC   = toDecimal(prediction.getTotalAmount() != null ? prediction.getTotalAmount().getValue() : null);
        BigDecimal totalHT    = toDecimal(prediction.getTotalNet() != null ? prediction.getTotalNet().getValue() : null);
        BigDecimal totalTVA   = toDecimal(prediction.getTotalTaxes());
        String currency = prediction.getLocaleField() != null && prediction.getLocaleField().getCurrency() != null
                ? prediction.getLocaleField().getCurrency() : "EUR";

        // Si HT non extrait, calculer depuis TTC - TVA
        if (totalHT == null && totalTTC != null && totalTVA != null) {
            totalHT = totalTTC.subtract(totalTVA);
        }
        if (totalTVA == null && totalTTC != null && totalHT != null) {
            totalTVA = totalTTC.subtract(totalHT);
        }

        log.info("Mindee parsed: vendor={} invoice={} date={} ttc={}", supplierName, invoiceNumber, invoiceDate, totalTTC);
        return new MindeeResult(supplierName, invoiceNumber, invoiceDate, dueDate, totalHT, totalTVA, totalTTC, currency);
    }

    private String safeStr(String v) { return (v != null && !v.isBlank()) ? v.trim() : null; }

    private BigDecimal toDecimal(Double v) {
        if (v == null) return null;
        return BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public record MindeeResult(
            String supplierName,
            String invoiceNumber,
            LocalDate invoiceDate,
            LocalDate dueDate,
            BigDecimal totalHT,
            BigDecimal totalTVA,
            BigDecimal totalTTC,
            String currency
    ) {}
}
