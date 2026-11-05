package com.logicielpourtous.site.features.accounting;

import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.*;
import com.stripe.net.Webhook;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/stripe")
public class StripeWebhookController {

    private final StripeAccountingService stripeService;
    private final TenantRepository tenantRepository;

    public StripeWebhookController(StripeAccountingService stripeService,
                                    TenantRepository tenantRepository) {
        this.stripeService = stripeService;
        this.tenantRepository = tenantRepository;
    }

    /**
     * Webhook Stripe par tenant.
     * URL à configurer dans Stripe : https://votredomaine.com/api/stripe/webhook/{tenantId}
     */
    @PostMapping("/webhook/{tenantId}")
    public ResponseEntity<String> webhook(
            @PathVariable Long tenantId,
            @RequestBody String payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String sigHeader) {

        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant == null) return ResponseEntity.notFound().build();

        Event event;
        try {
            String secret = stripeService.getWebhookSecret().orElse(null);
            if (secret != null && sigHeader != null) {
                event = Webhook.constructEvent(payload, sigHeader, secret);
            } else {
                event = com.stripe.net.ApiResource.GSON.fromJson(payload, Event.class);
            }
        } catch (SignatureVerificationException e) {
            return ResponseEntity.badRequest().body("Signature invalide");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Payload invalide");
        }

        try {
            processEvent(tenant, event);
        } catch (Exception ignored) {
            // Retourne 200 pour éviter les retries Stripe
        }
        return ResponseEntity.ok("ok");
    }

    private void processEvent(Tenant tenant, Event event) {
        switch (event.getType()) {
            case "payment_intent.succeeded" -> {
                EventDataObjectDeserializer deser = event.getDataObjectDeserializer();
                if (deser.getObject().isPresent()) {
                    PaymentIntent pi = (PaymentIntent) deser.getObject().get();
                    long amount = pi.getAmountReceived() != null ? pi.getAmountReceived() : pi.getAmount();
                    String desc = pi.getDescription() != null ? pi.getDescription() : "Paiement Stripe " + pi.getId();
                    stripeService.processEvent(tenant, "payment_intent.succeeded", pi.getId(),
                            amount, pi.getCurrency(), desc,
                            LocalDate.ofEpochDay(pi.getCreated() / 86400));
                }
            }
            case "payout.paid" -> {
                EventDataObjectDeserializer deser = event.getDataObjectDeserializer();
                if (deser.getObject().isPresent()) {
                    Payout payout = (Payout) deser.getObject().get();
                    stripeService.processEvent(tenant, "payout.paid", payout.getId(),
                            payout.getAmount(), payout.getCurrency(),
                            "Virement Stripe → Banque",
                            LocalDate.ofEpochDay(payout.getArrivalDate() / 86400));
                }
            }
            case "charge.refunded" -> {
                EventDataObjectDeserializer deser = event.getDataObjectDeserializer();
                if (deser.getObject().isPresent()) {
                    Charge charge = (Charge) deser.getObject().get();
                    long refund = charge.getAmountRefunded() != null ? charge.getAmountRefunded() : 0L;
                    stripeService.processEvent(tenant, "charge.refunded", "ref_" + charge.getId(),
                            refund, charge.getCurrency(),
                            "Remboursement Stripe",
                            LocalDate.ofEpochDay(charge.getCreated() / 86400));
                }
            }
        }
    }
}
