package com.logicielpourtous.site.features.shop;

import com.logicielpourtous.site.features.invoice.Invoice;
import com.logicielpourtous.site.features.invoice.InvoiceLine;
import com.logicielpourtous.site.features.invoice.InvoiceLineRepository;
import com.logicielpourtous.site.features.invoice.InvoiceRepository;
import com.logicielpourtous.site.features.order.Order;
import com.logicielpourtous.site.features.order.OrderLine;
import com.logicielpourtous.site.features.order.OrderLineRepository;
import com.logicielpourtous.site.features.order.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

@Service
public class ShopOrderConversionService {

    private final OrderRepository       orderRepository;
    private final OrderLineRepository   orderLineRepository;
    private final ShopOrderRepository   shopOrderRepository;
    private final InvoiceRepository     invoiceRepository;
    private final InvoiceLineRepository invoiceLineRepository;

    public ShopOrderConversionService(OrderRepository orderRepository,
                                      OrderLineRepository orderLineRepository,
                                      ShopOrderRepository shopOrderRepository,
                                      InvoiceRepository invoiceRepository,
                                      InvoiceLineRepository invoiceLineRepository) {
        this.orderRepository     = orderRepository;
        this.orderLineRepository = orderLineRepository;
        this.shopOrderRepository = shopOrderRepository;
        this.invoiceRepository   = invoiceRepository;
        this.invoiceLineRepository = invoiceLineRepository;
    }

    /**
     * Convertit une ShopOrder en Order interne si ce n'est pas déjà fait.
     * Retourne l'Order existant ou nouvellement créé.
     */
    @Transactional
    public Order convertToInternalOrder(ShopOrder shopOrder) {
        if (shopOrder.getInternalOrder() != null) {
            return shopOrder.getInternalOrder();
        }

        Order order = new Order();
        order.setSource(Order.Source.SHOP);
        order.setShopOrderRef(shopOrder.getReference());
        order.setCustomer(shopOrder.getCustomer());
        order.setOrderDate(shopOrder.getCreatedAt() != null
                ? shopOrder.getCreatedAt().toLocalDate() : LocalDate.now());
        order.setNotes(buildNotes(shopOrder));
        order.setStatus(Order.OrderStatus.CONFIRMED);
        orderRepository.save(order);

        order.setOrderNumber("CMD-" + LocalDate.now().getYear() + "-" + order.getId());
        orderRepository.save(order);

        BigDecimal totalHT  = BigDecimal.ZERO;
        BigDecimal totalTVA = BigDecimal.ZERO;

        for (ShopOrderLine sl : shopOrder.getLines()) {
            OrderLine line = new OrderLine();
            line.setOrder(order);
            line.setDescription(sl.getProductName());
            line.setQuantity(sl.getQuantity());
            line.setUnitPrice(sl.getUnitPriceHT());
            line.setTotalHT(sl.getLineTotalHT());

            BigDecimal rate = sl.getTaxRate() != null ? sl.getTaxRate() : BigDecimal.ZERO;
            line.setVatRate(rate);

            BigDecimal lineTVA = sl.getLineTotalHT() != null
                    ? sl.getLineTotalHT().multiply(rate).divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            line.setTotalTVA(lineTVA);

            if (sl.getShopProduct() != null && sl.getShopProduct().getProduct() != null) {
                line.setProduct(sl.getShopProduct().getProduct());
            }
            orderLineRepository.save(line);

            totalHT  = totalHT.add(sl.getLineTotalHT() != null ? sl.getLineTotalHT() : BigDecimal.ZERO);
            totalTVA = totalTVA.add(lineTVA);
        }

        order.setTotalAmount(totalHT);
        order.setTotalTVA(totalTVA);
        orderRepository.save(order);

        shopOrder.setInternalOrder(order);
        shopOrderRepository.save(shopOrder);

        return order;
    }

    /**
     * Après paiement Stripe confirmé : crée l'Order interne ET la facture marquée PAID.
     * Idempotent — ne recrée rien si déjà fait.
     */
    @Transactional
    public Order convertAndInvoiceStripeOrder(ShopOrder shopOrder) {
        Order order = convertToInternalOrder(shopOrder);

        // Ne recrée pas la facture si elle est déjà liée
        if (order.getInvoice() != null) return order;

        Invoice inv = new Invoice();
        inv.setType(Invoice.InvoiceType.SALE);
        inv.setStatus(Invoice.InvoiceStatus.PAID);
        inv.setCustomer(shopOrder.getCustomer());
        inv.setInvoiceDate(LocalDate.now());
        inv.setDueDate(LocalDate.now());
        inv.setTotalHT(shopOrder.getTotalHT()   != null ? shopOrder.getTotalHT()   : BigDecimal.ZERO);
        inv.setTotalTVA(shopOrder.getVatAmount() != null ? shopOrder.getVatAmount() : BigDecimal.ZERO);
        inv.setTotalTTC(shopOrder.getTotalTTC()  != null ? shopOrder.getTotalTTC()  : BigDecimal.ZERO);
        inv.setNotes("Paiement Stripe — réf. boutique : " + shopOrder.getReference());
        invoiceRepository.save(inv);
        inv.setInvoiceNumber("FAC-" + LocalDate.now().getYear() + "-" + inv.getId());
        invoiceRepository.save(inv);

        int pos = 0;
        for (ShopOrderLine sl : shopOrder.getLines()) {
            InvoiceLine il = new InvoiceLine();
            il.setInvoice(inv);
            il.setDescription(sl.getProductName());
            il.setQuantity(sl.getQuantity());
            il.setUnitPrice(sl.getUnitPriceHT()  != null ? sl.getUnitPriceHT()  : BigDecimal.ZERO);
            il.setTotalHT(sl.getLineTotalHT()    != null ? sl.getLineTotalHT()   : BigDecimal.ZERO);
            BigDecimal rate = sl.getTaxRate()    != null ? sl.getTaxRate()        : BigDecimal.ZERO;
            il.setVatRate(rate);
            il.setVatRateLabel(rate.stripTrailingZeros().toPlainString() + " %");
            BigDecimal lineTVA = il.getTotalHT().multiply(rate)
                    .divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);
            il.setTotalTVA(lineTVA);
            if (sl.getShopProduct() != null && sl.getShopProduct().getProduct() != null) {
                il.setProduct(sl.getShopProduct().getProduct());
            }
            il.setPosition(pos++);
            invoiceLineRepository.save(il);
        }

        // Lier la facture à l'Order interne
        order.setInvoice(inv);
        order.setStatus(Order.OrderStatus.DELIVERED);
        orderRepository.save(order);

        return order;
    }

    private String buildNotes(ShopOrder s) {
        StringBuilder sb = new StringBuilder();
        sb.append("Commande boutique : ").append(s.getReference());
        if (s.getCustomerName() != null) sb.append("\nClient : ").append(s.getCustomerName());
        if (s.getEmail() != null)        sb.append("\nEmail : ").append(s.getEmail());
        if (s.getPhone() != null)        sb.append("\nTél : ").append(s.getPhone());
        if (s.getShippingAddress() != null) {
            sb.append("\nLivraison : ").append(s.getShippingAddress());
            if (s.getShippingZip() != null)  sb.append(", ").append(s.getShippingZip());
            if (s.getShippingCity() != null)  sb.append(" ").append(s.getShippingCity());
        }
        if (s.getPaymentMethod() != null) sb.append("\nPaiement : ").append(s.getPaymentMethod().label);
        if (s.getNotes() != null && !s.getNotes().isBlank()) sb.append("\nNote client : ").append(s.getNotes());
        return sb.toString();
    }
}
