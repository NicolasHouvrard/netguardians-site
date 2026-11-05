package com.logicielpourtous.site.features.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.annotation.Secured;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/intra/payment")
@Secured("ROLE_ADMIN")
public class PaymentConfigController {

    @Value("${shop.base-url:http://localhost:8080}")
    private String baseUrl;

    private final PaymentConfigRepository repo;

    public PaymentConfigController(PaymentConfigRepository repo) {
        this.repo = repo;
    }

    // ── Virement bancaire ─────────────────────────────────────────────────────

    @GetMapping("/bank-transfer")
    public String bankTransfer(Model model) {
        model.addAttribute("config", getOrCreate(PaymentConfig.ConfigType.BANK_TRANSFER));
        return "intra/payment/bank-transfer";
    }

    @PostMapping("/bank-transfer")
    public String saveBankTransfer(@RequestParam(required = false) String iban,
                                   @RequestParam(required = false) String bic,
                                   @RequestParam(required = false) String beneficiaryName,
                                   @RequestParam(defaultValue = "false") boolean active,
                                   RedirectAttributes ra) {
        PaymentConfig config = getOrCreate(PaymentConfig.ConfigType.BANK_TRANSFER);
        if (iban != null && !iban.isBlank())
            config.setIban(iban.replaceAll("\\s+", "").toUpperCase());
        if (bic != null && !bic.isBlank())
            config.setBic(bic.trim().toUpperCase());
        config.setBeneficiaryName(beneficiaryName);
        config.setActive(active);
        repo.save(config);
        ra.addFlashAttribute("successMessage", "Coordonnées bancaires enregistrées.");
        return "redirect:/intra/payment/bank-transfer";
    }

    // ── Stripe ────────────────────────────────────────────────────────────────

    @GetMapping("/stripe")
    public String stripe(Model model) {
        model.addAttribute("config",   getOrCreate(PaymentConfig.ConfigType.STRIPE));
        model.addAttribute("baseUrl",  baseUrl);
        return "intra/payment/stripe";
    }

    @PostMapping("/stripe")
    public String saveStripe(@RequestParam(required = false) String stripeSecretKey,
                             @RequestParam(required = false) String stripePublishableKey,
                             @RequestParam(required = false) String stripeWebhookSecret,
                             @RequestParam(defaultValue = "false") boolean active,
                             RedirectAttributes ra) {
        PaymentConfig config = getOrCreate(PaymentConfig.ConfigType.STRIPE);
        if (stripeSecretKey != null && !stripeSecretKey.isBlank())
            config.setStripeSecretKey(stripeSecretKey.trim());
        if (stripePublishableKey != null && !stripePublishableKey.isBlank())
            config.setStripePublishableKey(stripePublishableKey.trim());
        if (stripeWebhookSecret != null && !stripeWebhookSecret.isBlank())
            config.setStripeWebhookSecret(stripeWebhookSecret.trim());
        config.setActive(active);
        repo.save(config);
        ra.addFlashAttribute("successMessage", "Configuration Stripe enregistrée.");
        return "redirect:/intra/payment/stripe";
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private PaymentConfig getOrCreate(PaymentConfig.ConfigType type) {
        return repo.findByType(type).orElseGet(() -> {
            PaymentConfig pc = new PaymentConfig();
            pc.setType(type);
            return pc;
        });
    }
}
