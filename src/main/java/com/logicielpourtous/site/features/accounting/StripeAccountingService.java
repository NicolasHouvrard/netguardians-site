package com.logicielpourtous.site.features.accounting;

import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.payment.PaymentConfig;
import com.logicielpourtous.site.features.payment.PaymentConfigRepository;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Payout;
import com.stripe.param.ChargeListParams;
import com.stripe.param.PayoutListParams;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Service
public class StripeAccountingService {

    private final PaymentConfigRepository paymentConfigRepo;
    private final StripeAccountingMappingRepository mappingRepo;
    private final StripeTransactionRepository txRepo;
    private final AccountingEntryRepository entryRepo;
    private final AccountingJournalRepository journalRepo;
    private final AccountingAccountRepository accountRepo;
    private final AccountingService accountingService;

    public StripeAccountingService(PaymentConfigRepository paymentConfigRepo,
                                   StripeAccountingMappingRepository mappingRepo,
                                   StripeTransactionRepository txRepo,
                                   AccountingEntryRepository entryRepo,
                                   AccountingJournalRepository journalRepo,
                                   AccountingAccountRepository accountRepo,
                                   AccountingService accountingService) {
        this.paymentConfigRepo = paymentConfigRepo;
        this.mappingRepo = mappingRepo;
        this.txRepo = txRepo;
        this.entryRepo = entryRepo;
        this.journalRepo = journalRepo;
        this.accountRepo = accountRepo;
        this.accountingService = accountingService;
    }

    public Optional<String> getStripeSecretKey() {
        return paymentConfigRepo.findByType(PaymentConfig.ConfigType.STRIPE)
                .filter(PaymentConfig::hasStripeSecretKey)
                .map(PaymentConfig::getStripeSecretKey);
    }

    public Optional<String> getWebhookSecret() {
        return paymentConfigRepo.findByType(PaymentConfig.ConfigType.STRIPE)
                .map(PaymentConfig::getStripeWebhookSecret)
                .filter(s -> s != null && !s.isBlank());
    }

    public boolean isConfigured() {
        return getStripeSecretKey().isPresent();
    }

    @Transactional
    public StripeImportResult processEvent(Tenant tenant, String eventType, String stripeId,
                                           long amountCents, String currency,
                                           String description, LocalDate eventDate) {
        if (txRepo.existsByStripeId(stripeId)) {
            return new StripeImportResult(false, "SKIPPED", "Déjà importé");
        }

        // Toutes les transactions sont importées sans écriture automatique — le rapprochement est manuel
        // Le mapping définit la suggestion comptable visible sur la page détail, pas une écriture auto
        String mappingNote = mappingRepo.findByEventTypeAndTenantAndActiveTrue(eventType, tenant).isEmpty()
                ? "Aucun mapping configuré pour " + eventType : null;
        saveTransaction(tenant, stripeId, eventType, amountCents, currency, description, eventDate,
                "IMPORTED", null, mappingNote, null);
        return new StripeImportResult(true, "IMPORTED", stripeId);
    }

    @Transactional
    public SyncResult syncFromStripe(Tenant tenant, int days) throws StripeException {
        String key = getStripeSecretKey()
                .orElseThrow(() -> new IllegalStateException("Clé Stripe non configurée dans /intra/payment/stripe"));
        Stripe.apiKey = key;

        long since = Instant.now().minus(days, ChronoUnit.DAYS).getEpochSecond();
        int imported = 0, skipped = 0, errors = 0;

        var piParams = com.stripe.param.PaymentIntentListParams.builder()
                .setCreated(com.stripe.param.PaymentIntentListParams.Created.builder().setGte(since).build())
                .setLimit(100L)
                .build();
        for (PaymentIntent pi : PaymentIntent.list(piParams).autoPagingIterable()) {
            if (!"succeeded".equals(pi.getStatus())) continue;
            long amount = pi.getAmountReceived() != null ? pi.getAmountReceived() : pi.getAmount();
            String desc = pi.getDescription() != null ? pi.getDescription() : "Paiement Stripe " + pi.getId();
            StripeImportResult r = processEvent(tenant, "payment_intent.succeeded", pi.getId(),
                    amount, pi.getCurrency(), desc,
                    LocalDate.ofEpochDay(pi.getCreated() / 86400));
            if (r.success()) imported++; else if ("SKIPPED".equals(r.status())) skipped++; else errors++;
        }

        var poParams = PayoutListParams.builder()
                .setArrivalDate(PayoutListParams.ArrivalDate.builder().setGte(since).build())
                .setLimit(100L)
                .build();
        for (Payout payout : Payout.list(poParams).autoPagingIterable()) {
            if (!"paid".equals(payout.getStatus())) continue;
            StripeImportResult r = processEvent(tenant, "payout.paid", payout.getId(),
                    payout.getAmount(), payout.getCurrency(),
                    "Virement Stripe → Banque " + payout.getId(),
                    LocalDate.ofEpochDay(payout.getArrivalDate() / 86400));
            if (r.success()) imported++; else if ("SKIPPED".equals(r.status())) skipped++; else errors++;
        }

        var chParams = ChargeListParams.builder()
                .setCreated(ChargeListParams.Created.builder().setGte(since).build())
                .setLimit(100L)
                .build();
        for (Charge charge : Charge.list(chParams).autoPagingIterable()) {
            if (!Boolean.TRUE.equals(charge.getRefunded())) continue;
            long refundAmt = charge.getAmountRefunded() != null ? charge.getAmountRefunded() : charge.getAmount();
            StripeImportResult r = processEvent(tenant, "charge.refunded", "ref_" + charge.getId(),
                    refundAmt, charge.getCurrency(),
                    "Remboursement Stripe " + charge.getId(),
                    LocalDate.ofEpochDay(charge.getCreated() / 86400));
            if (r.success()) imported++; else if ("SKIPPED".equals(r.status())) skipped++; else errors++;
        }

        return new SyncResult(imported, skipped, errors);
    }

    private void saveTransaction(Tenant tenant, String stripeId, String eventType, long amountCents,
                                  String currency, String description, LocalDate date,
                                  String status, Long entryId, String error, String bankAccountNumber) {
        StripeTransaction tx = new StripeTransaction();
        tx.setTenant(tenant);
        tx.setStripeId(stripeId);
        tx.setStripeEventType(eventType);
        tx.setAmount(centsToBigDecimal(amountCents));
        tx.setCurrency(currency);
        tx.setDescription(description);
        tx.setEventDate(date);
        tx.setStripeStatus(status);
        tx.setAccountingEntryId(entryId);
        tx.setImportedAt(LocalDateTime.now());
        tx.setErrorMessage(error);
        tx.setBankAccountNumber(bankAccountNumber);
        txRepo.save(tx);
    }

    private BigDecimal centsToBigDecimal(long cents) {
        return BigDecimal.valueOf(cents).divide(BigDecimal.valueOf(100));
    }

    public record StripeImportResult(boolean success, String status, String message) {}
    public record SyncResult(int imported, int skipped, int errors) {}
}
