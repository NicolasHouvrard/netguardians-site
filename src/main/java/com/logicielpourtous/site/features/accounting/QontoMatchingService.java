package com.logicielpourtous.site.features.accounting;

import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.invoice.Invoice;
import com.logicielpourtous.site.features.invoice.InvoiceRepository;
import com.logicielpourtous.site.features.shop.ShopOrder;
import com.logicielpourtous.site.features.shop.ShopOrderConversionService;
import com.logicielpourtous.site.features.shop.ShopOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rapprochement automatique des virements Qonto avec les commandes et factures.
 *
 * Patterns reconnus dans le libellé ou la référence de la transaction :
 *   CMD-[0-9A-F]{8}  → référence de commande boutique (ex: CMD-A1B2C3D4)
 *   FAC-\d{4}-\d+    → numéro de facture interne (ex: FAC-2026-12)
 */
@Service
public class QontoMatchingService {

    private static final Logger log = LoggerFactory.getLogger(QontoMatchingService.class);

    private static final Pattern SHOP_ORDER_PATTERN = Pattern.compile("\\b(CMD-[0-9A-F]{8})\\b");
    private static final Pattern INVOICE_PATTERN    = Pattern.compile("\\b(FAC-\\d{4}-\\d+)\\b");

    private final ShopOrderRepository        shopOrderRepository;
    private final InvoiceRepository          invoiceRepository;
    private final QontoTransactionRepository txRepo;
    private final ShopOrderConversionService conversionService;

    public QontoMatchingService(ShopOrderRepository shopOrderRepository,
                                InvoiceRepository invoiceRepository,
                                QontoTransactionRepository txRepo,
                                ShopOrderConversionService conversionService) {
        this.shopOrderRepository = shopOrderRepository;
        this.invoiceRepository   = invoiceRepository;
        this.txRepo              = txRepo;
        this.conversionService   = conversionService;
    }

    /**
     * Lance le rapprochement sur toutes les transactions crédit non rapprochées du tenant.
     * Retourne le nombre de rapprochements réussis.
     */
    @Transactional
    public int matchUnreconciled(Tenant tenant) {
        List<QontoTransaction> credits =
                txRepo.findByTenantAndReconciledFalseAndSideOrderByImportedAtDesc(tenant, "credit");
        int matched = 0;
        for (QontoTransaction tx : credits) {
            try {
                if (matchTransaction(tx)) matched++;
            } catch (Exception e) {
                log.warn("Erreur rapprochement tx {} : {}", tx.getQontoId(), e.getMessage());
            }
        }
        return matched;
    }

    /**
     * Tente de rapprocher une transaction unique.
     * Retourne true si un rapprochement a été effectué.
     */
    @Transactional
    public boolean matchTransaction(QontoTransaction tx) {
        if (!"credit".equalsIgnoreCase(tx.getSide())) return false;
        if (tx.isReconciled()) return false;

        String text = buildSearchText(tx).toUpperCase();

        // 1. Commande boutique
        Matcher m1 = SHOP_ORDER_PATTERN.matcher(text);
        if (m1.find()) {
            String ref = m1.group(1);
            ShopOrder order = shopOrderRepository.findByReference(ref).orElse(null);
            if (order != null && isAwaitingPayment(order)) {
                order.setStatus(ShopOrder.OrderStatus.PAID);
                order.setPaidAt(LocalDateTime.now());
                shopOrderRepository.save(order);
                conversionService.convertAndInvoiceStripeOrder(order);
                tx.setReconciled(true);
                txRepo.save(tx);
                log.info("Rapprochement Qonto {} → commande boutique {}", tx.getQontoId(), ref);
                return true;
            }
        }

        // 2. Facture interne
        Matcher m2 = INVOICE_PATTERN.matcher(text);
        if (m2.find()) {
            String invNum = m2.group(1);
            Invoice inv = invoiceRepository.findByInvoiceNumber(invNum).orElse(null);
            if (inv != null && inv.getStatus() != Invoice.InvoiceStatus.PAID
                            && inv.getStatus() != Invoice.InvoiceStatus.CANCELLED) {
                inv.setStatus(Invoice.InvoiceStatus.PAID);
                invoiceRepository.save(inv);
                tx.setReconciled(true);
                txRepo.save(tx);
                log.info("Rapprochement Qonto {} → facture {}", tx.getQontoId(), invNum);
                return true;
            }
        }

        return false;
    }

    private boolean isAwaitingPayment(ShopOrder order) {
        return order.getStatus() == ShopOrder.OrderStatus.AWAITING_TRANSFER
            || order.getStatus() == ShopOrder.OrderStatus.PENDING_PAYMENT;
    }

    private String buildSearchText(QontoTransaction tx) {
        return (tx.getLabel() != null ? tx.getLabel() : "")
             + " " + (tx.getReference() != null ? tx.getReference() : "");
    }
}
