package com.logicielpourtous.site.features.shop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class StripeService {

    private static final Logger log = LoggerFactory.getLogger(StripeService.class);
    private static final String STRIPE_API = "https://api.stripe.com/v1";

    private final ShopConfigService shopConfigService;
    private final WebClient webClient = WebClient.builder().build();
    private final ObjectMapper mapper  = new ObjectMapper();

    public StripeService(ShopConfigService shopConfigService) {
        this.shopConfigService = shopConfigService;
    }

    public boolean isConfigured() {
        String key = shopConfigService.getStripeSecretKey();
        return key != null && !key.isBlank();
    }

    public String createCheckoutSession(ShopOrder order, String successUrl, String cancelUrl) throws Exception {
        String secretKey = shopConfigService.getStripeSecretKey();
        if (secretKey == null || secretKey.isBlank()) throw new RuntimeException("Stripe non configuré.");

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("mode", "payment");
        body.add("success_url", successUrl);
        body.add("cancel_url", cancelUrl);
        body.add("customer_email", order.getEmail());
        body.add("payment_method_types[]", "card");

        long amountCents = order.getTotalTTC()
                .multiply(new BigDecimal("100"))
                .setScale(0, RoundingMode.HALF_UP).longValue();

        body.add("line_items[0][price_data][currency]", "eur");
        body.add("line_items[0][price_data][unit_amount]", String.valueOf(amountCents));
        body.add("line_items[0][price_data][product_data][name]", "Commande " + order.getReference());
        body.add("line_items[0][quantity]", "1");
        body.add("metadata[order_ref]", order.getReference());

        String response = webClient.post()
                .uri(STRIPE_API + "/checkout/sessions")
                .header("Authorization", "Bearer " + secretKey)
                .body(BodyInserters.fromFormData(body))
                .retrieve()
                .bodyToMono(String.class)
                .block();

        JsonNode node = mapper.readTree(response);
        if (node.has("error"))
            throw new RuntimeException("Stripe: " + node.path("error").path("message").asText());

        return node.path("url").asText();
    }

    public boolean isSessionPaid(String sessionId) {
        String secretKey = shopConfigService.getStripeSecretKey();
        if (secretKey == null || sessionId == null) return false;
        try {
            String response = webClient.get()
                    .uri(STRIPE_API + "/checkout/sessions/" + sessionId)
                    .header("Authorization", "Bearer " + secretKey)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode node = mapper.readTree(response);
            return "paid".equals(node.path("payment_status").asText());
        } catch (Exception e) {
            log.error("Erreur vérification Stripe session : {}", e.getMessage());
            return false;
        }
    }
}
