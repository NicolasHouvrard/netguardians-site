package com.logicielpourtous.site.features.order;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.invoice.Invoice;
import com.logicielpourtous.site.features.invoice.InvoiceLine;
import com.logicielpourtous.site.features.invoice.InvoiceLineRepository;
import com.logicielpourtous.site.features.invoice.InvoiceRepository;
import com.logicielpourtous.site.features.product.ProductRepository;
import com.logicielpourtous.site.features.request.RequestRepository;
import com.logicielpourtous.site.features.shop.ShopOrder;
import com.logicielpourtous.site.features.shop.ShopOrderRepository;
import com.logicielpourtous.site.features.purchase.SupplierInvoiceRepository;
import com.logicielpourtous.site.features.supplier.IngramOrderService;
import com.logicielpourtous.site.features.team.TeamRepository;
import com.logicielpourtous.site.features.vatrate.VatRateRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Controller
@RequestMapping("/intra/orders")
public class OrderController {

    private final OrderRepository       orderRepository;
    private final OrderLineRepository   orderLineRepository;
    private final CustomerRepository    customerRepository;
    private final RequestRepository     requestRepository;
    private final ProductRepository     productRepository;
    private final VatRateRepository     vatRateRepository;
    private final InvoiceRepository     invoiceRepository;
    private final InvoiceLineRepository invoiceLineRepository;
    private final ShopOrderRepository   shopOrderRepository;
    private final TeamRepository        teamRepository;
    private final IngramOrderService       ingramOrderService;
    private final SupplierInvoiceRepository supplierInvoiceRepository;

    public OrderController(OrderRepository orderRepository,
                           OrderLineRepository orderLineRepository,
                           CustomerRepository customerRepository,
                           RequestRepository requestRepository,
                           ProductRepository productRepository,
                           VatRateRepository vatRateRepository,
                           InvoiceRepository invoiceRepository,
                           InvoiceLineRepository invoiceLineRepository,
                           ShopOrderRepository shopOrderRepository,
                           TeamRepository teamRepository,
                           IngramOrderService ingramOrderService,
                           SupplierInvoiceRepository supplierInvoiceRepository) {
        this.orderRepository          = orderRepository;
        this.orderLineRepository      = orderLineRepository;
        this.customerRepository       = customerRepository;
        this.requestRepository        = requestRepository;
        this.productRepository        = productRepository;
        this.vatRateRepository        = vatRateRepository;
        this.invoiceRepository        = invoiceRepository;
        this.invoiceLineRepository    = invoiceLineRepository;
        this.shopOrderRepository      = shopOrderRepository;
        this.teamRepository           = teamRepository;
        this.ingramOrderService       = ingramOrderService;
        this.supplierInvoiceRepository = supplierInvoiceRepository;
    }

    // ── Liste ─────────────────────────────────────────────────────────────────

    @GetMapping
    public String list(@RequestParam(required = false) String tab,
                       @RequestParam(required = false) String shopStatus,
                       Model model) {
        // ── Devis & commandes internes ────────────────────────────────────────
        model.addAttribute("orders",     orderRepository.findAllByOrderByOrderDateDesc());
        model.addAttribute("pending",    orderRepository.countByStatus(Order.OrderStatus.PENDING));
        model.addAttribute("inProgress", orderRepository.countByStatus(Order.OrderStatus.IN_PROGRESS));
        model.addAttribute("customers",  customerRepository.findAll());

        // ── Commandes boutique ────────────────────────────────────────────────
        model.addAttribute("shopOrders",
                shopStatus != null && !shopStatus.isBlank()
                        ? shopOrderRepository.findByStatusOrderByCreatedAtDesc(ShopOrder.OrderStatus.valueOf(shopStatus))
                        : shopOrderRepository.findAllOrderByCreatedAtDesc());
        model.addAttribute("shopStatuses",          ShopOrder.OrderStatus.values());
        model.addAttribute("currentShopStatus",     shopStatus);
        model.addAttribute("shopPendingValidation",
                shopOrderRepository.countByStatus(ShopOrder.OrderStatus.PENDING_VALIDATION));

        model.addAttribute("activeTab", "shop".equals(tab) ? "shop" : "internal");
        return "intra/orders/list";
    }

    // ── Nouveau devis ──────────────────────────────────────────────────────────

    @GetMapping("/new")
    public String newForm(@RequestParam(required = false) Long ticketId, Model model) {
        model.addAttribute("ticket",    ticketId != null
                ? requestRepository.findById(ticketId).orElse(null) : null);
        model.addAttribute("customers", customerRepository.findAll());
        return "intra/orders/form";
    }

    @PostMapping
    public String create(@RequestParam Long customerId,
                         @RequestParam(required = false) Long ticketId,
                         @RequestParam(required = false) LocalDate orderDate,
                         @RequestParam(required = false) LocalDate expectedDate,
                         @RequestParam(required = false) String notes,
                         RedirectAttributes ra) {
        Order order = new Order();
        customerRepository.findById(customerId).ifPresent(order::setCustomer);
        if (ticketId != null) requestRepository.findById(ticketId).ifPresent(order::setTicket);
        order.setOrderDate(orderDate != null ? orderDate : LocalDate.now());
        order.setExpectedDate(expectedDate);
        order.setNotes(notes);
        order.setStatus(Order.OrderStatus.PENDING);
        order.setCreatedAt(LocalDateTime.now());
        orderRepository.save(order);
        order.setOrderNumber("DEV-" + LocalDate.now().getYear() + "-" + order.getId());
        orderRepository.save(order);

        ra.addFlashAttribute("successMessage", "Devis " + order.getOrderNumber() + " créé.");
        if (ticketId != null) return "redirect:/intra/tickets/" + ticketId;
        return "redirect:/intra/orders/" + order.getId();
    }

    // ── Détail ─────────────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public String detail(@PathVariable Long id, Model model) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Commande introuvable : " + id));
        order.getLines().size();
        if (order.getCustomer() != null) order.getCustomer().getCompanyName();
        if (order.getTicket()   != null) { order.getTicket().getId(); order.getTicket().getTitle(); }

        Customer.FiscalZone zone = order.getCustomer() != null
                ? order.getCustomer().getFiscalZone() : Customer.FiscalZone.FRANCE;

        model.addAttribute("order",              order);
        model.addAttribute("statuses",           Order.OrderStatus.values());
        model.addAttribute("deliveryModes",      Order.DeliveryMode.values());
        model.addAttribute("vatRates",           vatRateRepository.findByZoneAndActiveTrueOrderByRateDesc(zone));
        model.addAttribute("vatSummary",         buildVatSummary(order));
        model.addAttribute("teams",              teamRepository.findAll());
        model.addAttribute("supplierInvoices",   supplierInvoiceRepository.findByOrderIdOrderByCreatedAtDesc(id));
        return "intra/orders/detail";
    }

    // ── Mettre à jour infos ────────────────────────────────────────────────────

    @PostMapping("/{id}/update")
    public String update(@PathVariable Long id,
                         @RequestParam(required = false) LocalDate orderDate,
                         @RequestParam(required = false) LocalDate expectedDate,
                         @RequestParam(required = false) String notes,
                         RedirectAttributes ra) {
        Order order = orderRepository.findById(id).orElseThrow();
        order.setOrderDate(orderDate);
        order.setExpectedDate(expectedDate);
        order.setNotes(notes);
        orderRepository.save(order);
        ra.addFlashAttribute("successMessage", "Devis mis à jour.");
        return "redirect:/intra/orders/" + id;
    }

    // ── Assigner une équipe ────────────────────────────────────────────────────

    @PostMapping("/{id}/team")
    public String assignTeam(@PathVariable Long id,
                             @RequestParam(required = false) Long teamId,
                             RedirectAttributes ra) {
        Order order = orderRepository.findById(id).orElseThrow();
        order.setTeam(teamId != null ? teamRepository.findById(teamId).orElse(null) : null);
        orderRepository.save(order);
        ra.addFlashAttribute("successMessage", teamId != null ? "Équipe assignée." : "Équipe retirée.");
        return "redirect:/intra/orders/" + id;
    }

    // ── Changer statut ─────────────────────────────────────────────────────────

    @PostMapping("/{id}/status")
    public String updateStatus(@PathVariable Long id,
                               @RequestParam Order.OrderStatus status,
                               RedirectAttributes ra) {
        Order order = orderRepository.findById(id).orElseThrow();
        boolean wasNotConfirmed = order.getStatus() != Order.OrderStatus.CONFIRMED;
        if (status == Order.OrderStatus.CONFIRMED
                && order.getOrderNumber() != null
                && order.getOrderNumber().startsWith("DEV-")) {
            order.setOrderNumber(order.getOrderNumber().replace("DEV-", "CMD-"));
        }
        order.setStatus(status);
        orderRepository.save(order);
        ra.addFlashAttribute("successMessage", "Statut : " + status.label + ".");

        // Passage automatique chez Ingram à la confirmation (si pas déjà commandé)
        if (status == Order.OrderStatus.CONFIRMED && wasNotConfirmed
                && order.getIngramOrderNumber() == null) {
            ingramOrderService.placeOrderAsync(id);
            ra.addFlashAttribute("successMessage",
                    "Statut : " + status.label + ". Commande Ingram lancée en arrière-plan.");
        }
        return "redirect:/intra/orders/" + id;
    }

    // ── Lignes ─────────────────────────────────────────────────────────────────

    @PostMapping("/{id}/lines")
    public String addLine(@PathVariable Long id,
                          @RequestParam(required = false) Long productId,
                          @RequestParam String description,
                          @RequestParam(defaultValue = "1") Integer quantity,
                          @RequestParam BigDecimal unitPrice,
                          @RequestParam(required = false) Long vatRateId,
                          @RequestParam(required = false) BigDecimal costPrice,
                          RedirectAttributes ra) {
        Order order = orderRepository.findById(id).orElseThrow();
        OrderLine line = new OrderLine();
        line.setOrder(order);
        line.setDescription(description);
        line.setQuantity(quantity);
        line.setUnitPrice(unitPrice);
        line.setTotalHT(unitPrice.multiply(BigDecimal.valueOf(quantity)));
        if (productId != null) productRepository.findById(productId).ifPresent(line::setProduct);

        if (vatRateId != null) {
            vatRateRepository.findById(vatRateId).ifPresent(vr -> {
                line.setVatRate(vr.getRate());
                line.setVatRateLabel(vr.getName());
            });
        } else {
            Customer.FiscalZone zone = order.getCustomer() != null
                    ? order.getCustomer().getFiscalZone() : Customer.FiscalZone.FRANCE;
            vatRateRepository.findFirstByZoneAndIsDefaultTrueAndActiveTrue(zone).ifPresent(vr -> {
                line.setVatRate(vr.getRate());
                line.setVatRateLabel(vr.getName());
            });
        }

        if (costPrice != null && costPrice.compareTo(BigDecimal.ZERO) > 0) {
            line.setCostPrice(costPrice);
            line.setTotalCost(costPrice.multiply(BigDecimal.valueOf(quantity)));
        }

        orderLineRepository.save(line);
        recalcTotal(id);
        ra.addFlashAttribute("successMessage", "Ligne ajoutée.");
        return "redirect:/intra/orders/" + id;
    }

    @PostMapping("/{id}/lines/{lineId}/update")
    public String updateLine(@PathVariable Long id,
                             @PathVariable Long lineId,
                             @RequestParam String description,
                             @RequestParam(defaultValue = "1") Integer quantity,
                             @RequestParam BigDecimal unitPrice,
                             @RequestParam(required = false) Long vatRateId,
                             @RequestParam(required = false) BigDecimal costPrice,
                             RedirectAttributes ra) {
        orderRepository.findById(id).orElseThrow();
        orderLineRepository.findById(lineId)
                .filter(l -> l.getOrder().getId().equals(id))
                .ifPresent(line -> {
                    line.setDescription(description);
                    line.setQuantity(quantity);
                    line.setUnitPrice(unitPrice);
                    line.setTotalHT(unitPrice.multiply(BigDecimal.valueOf(quantity)));
                    if (vatRateId != null) {
                        vatRateRepository.findById(vatRateId).ifPresent(vr -> {
                            line.setVatRate(vr.getRate());
                            line.setVatRateLabel(vr.getName());
                        });
                    }
                    if (costPrice != null && costPrice.compareTo(BigDecimal.ZERO) > 0) {
                        line.setCostPrice(costPrice);
                        line.setTotalCost(costPrice.multiply(BigDecimal.valueOf(quantity)));
                    } else {
                        line.setCostPrice(null);
                        line.setTotalCost(null);
                    }
                    orderLineRepository.save(line);
                });
        recalcTotal(id);
        ra.addFlashAttribute("successMessage", "Ligne mise à jour.");
        return "redirect:/intra/orders/" + id;
    }

    @PostMapping("/{id}/lines/{lineId}/delete")
    public String deleteLine(@PathVariable Long id,
                             @PathVariable Long lineId,
                             RedirectAttributes ra) {
        orderLineRepository.findById(lineId)
                .filter(l -> l.getOrder().getId().equals(id))
                .ifPresent(orderLineRepository::delete);
        recalcTotal(id);
        ra.addFlashAttribute("successMessage", "Ligne supprimée.");
        return "redirect:/intra/orders/" + id;
    }

    // ── Créer une facture depuis la commande ───────────────────────────────────

    @PostMapping("/{id}/create-invoice")
    @Transactional
    public String createInvoice(@PathVariable Long id, RedirectAttributes ra) {
        Order order = orderRepository.findById(id).orElseThrow();
        order.getLines().size();

        Invoice inv = new Invoice();
        inv.setCustomer(order.getCustomer());
        inv.setTicket(order.getTicket());
        inv.setType(Invoice.InvoiceType.SALE);
        inv.setStatus(Invoice.InvoiceStatus.DRAFT);
        inv.setInvoiceDate(LocalDate.now());
        inv.setDueDate(LocalDate.now().plusDays(30));
        inv.setNotes(order.getNotes());
        inv.setTotalHT(order.getTotalAmount()  != null ? order.getTotalAmount()  : BigDecimal.ZERO);
        inv.setTotalTVA(order.getTotalTVA()    != null ? order.getTotalTVA()     : BigDecimal.ZERO);
        inv.setTotalTTC(inv.getTotalHT().add(inv.getTotalTVA()));
        inv.setTotalCost(order.getTotalCost()  != null ? order.getTotalCost()    : BigDecimal.ZERO);
        invoiceRepository.save(inv);
        inv.setInvoiceNumber("FAC-" + LocalDate.now().getYear() + "-" + inv.getId());
        invoiceRepository.save(inv);

        int pos = 0;
        for (OrderLine ol : order.getLines()) {
            InvoiceLine il = new InvoiceLine();
            il.setInvoice(inv);
            il.setProduct(ol.getProduct());
            il.setDescription(ol.getDescription());
            il.setQuantity(ol.getQuantity());
            il.setUnitPrice(ol.getUnitPrice());
            il.setTotalHT(ol.getTotalHT());
            il.setVatRate(ol.getVatRate());
            il.setVatRateLabel(ol.getVatRateLabel());
            il.setTotalTVA(ol.getTotalTVA());
            il.setCostPrice(ol.getCostPrice());
            il.setTotalCost(ol.getTotalCost());
            il.setPosition(pos++);
            invoiceLineRepository.save(il);
        }

        ra.addFlashAttribute("successMessage", "Facture " + inv.getInvoiceNumber() + " créée.");
        return "redirect:/intra/invoices/" + inv.getId();
    }

    // ── Adresse de livraison ──────────────────────────────────────────────────

    @PostMapping("/{id}/delivery-address")
    public String updateDeliveryAddress(@PathVariable Long id,
                                        @RequestParam(required = false) String deliveryCompany,
                                        @RequestParam(required = false) String deliveryContact,
                                        @RequestParam(required = false) String deliveryAddress1,
                                        @RequestParam(required = false) String deliveryAddress2,
                                        @RequestParam(required = false) String deliveryPostalCode,
                                        @RequestParam(required = false) String deliveryCity,
                                        @RequestParam(defaultValue = "FR") String deliveryCountry,
                                        @RequestParam(required = false) String deliveryPhone,
                                        RedirectAttributes ra) {
        Order order = orderRepository.findById(id).orElseThrow();
        order.setDeliveryCompany(deliveryCompany);
        order.setDeliveryContact(deliveryContact);
        order.setDeliveryAddress1(deliveryAddress1);
        order.setDeliveryAddress2(deliveryAddress2);
        order.setDeliveryPostalCode(deliveryPostalCode);
        order.setDeliveryCity(deliveryCity);
        order.setDeliveryCountry(deliveryCountry != null && !deliveryCountry.isBlank() ? deliveryCountry : "FR");
        order.setDeliveryPhone(deliveryPhone);
        orderRepository.save(order);
        ra.addFlashAttribute("successMessage", "Adresse de livraison enregistrée.");
        return "redirect:/intra/orders/" + id;
    }

    // ── Mode de livraison ─────────────────────────────────────────────────────

    @PostMapping("/{id}/delivery-mode")
    public String updateDeliveryMode(@PathVariable Long id,
                                     @RequestParam Order.DeliveryMode deliveryMode,
                                     RedirectAttributes ra) {
        Order order = orderRepository.findById(id).orElseThrow();
        order.setDeliveryMode(deliveryMode);
        orderRepository.save(order);
        ra.addFlashAttribute("successMessage", "Mode de livraison : " + deliveryMode.label + ".");
        return "redirect:/intra/orders/" + id;
    }

    // ── Récapitulatif commandes fournisseurs ──────────────────────────────────

    @GetMapping("/supplier-orders")
    public String supplierOrders(Model model) {
        List<Order> orders = orderRepository.findByIngramOrderNumberIsNotNullOrderByIngramOrderedAtDesc();
        java.math.BigDecimal totalHT = orders.stream()
                .map(o -> o.getTotalAmount() != null ? o.getTotalAmount() : java.math.BigDecimal.ZERO)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        long invoiceCount = orders.stream().filter(o -> o.getInvoice() != null).count();
        model.addAttribute("orders",       orders);
        model.addAttribute("totalHT",      totalHT);
        model.addAttribute("invoiceCount", invoiceCount);
        return "intra/orders/supplier-orders";
    }

    // ── Détails Ingram en temps réel ──────────────────────────────────────────

    @GetMapping("/{id}/ingram-details")
    public String ingramDetails(@PathVariable Long id, Model model, RedirectAttributes ra) {
        Order order = orderRepository.findById(id).orElseThrow();
        if (order.getIngramOrderNumber() == null) {
            ra.addFlashAttribute("errorMessage", "Aucune commande Ingram associée à cette commande.");
            return "redirect:/intra/orders/" + id;
        }
        try {
            var details = ingramOrderService.fetchOrderDetails(id);
            model.addAttribute("order",   order);
            model.addAttribute("details", details);
            return "intra/orders/ingram-detail";
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", "Impossible de récupérer les détails Ingram : " + e.getMessage());
            return "redirect:/intra/orders/" + id;
        }
    }

    // ── Relancer manuellement la commande Ingram ───────────────────────────────

    @PostMapping("/{id}/ingram-order")
    public String placeIngramOrder(@PathVariable Long id, RedirectAttributes ra) {
        ingramOrderService.placeOrderAsync(id);
        ra.addFlashAttribute("successMessage", "Commande Ingram lancée en arrière-plan.");
        return "redirect:/intra/orders/" + id;
    }

    // ── Bon de livraison (vue imprimable) ──────────────────────────────────────

    @GetMapping("/{id}/delivery-note")
    @Transactional(readOnly = true)
    public String deliveryNote(@PathVariable Long id,
                               @AuthenticationPrincipal CustomUserDetails principal,
                               Model model) {
        Order order = orderRepository.findById(id).orElseThrow();
        order.getLines().size();
        if (order.getCustomer() != null) order.getCustomer().getCompanyName();
        model.addAttribute("order", order);
        if (principal != null) model.addAttribute("tenant", principal.getTenant());
        return "intra/orders/delivery-note";
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private void recalcTotal(Long orderId) {
        orderRepository.findById(orderId).ifPresent(order -> {
            BigDecimal ht = BigDecimal.ZERO, tva = BigDecimal.ZERO, cost = BigDecimal.ZERO;
            for (OrderLine line : order.getLines()) {
                BigDecimal lineHT = line.getTotalHT() != null ? line.getTotalHT() : BigDecimal.ZERO;
                BigDecimal rate   = line.getVatRate() != null ? line.getVatRate()  : BigDecimal.ZERO;
                BigDecimal lineTVA = lineHT.multiply(rate.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP))
                        .setScale(2, RoundingMode.HALF_UP);
                line.setTotalTVA(lineTVA);
                orderLineRepository.save(line);
                ht   = ht.add(lineHT);
                tva  = tva.add(lineTVA);
                if (line.getTotalCost() != null) cost = cost.add(line.getTotalCost());
            }
            order.setTotalAmount(ht);
            order.setTotalTVA(tva);
            order.setTotalCost(cost);
            orderRepository.save(order);
        });
    }

    private List<Map<String, Object>> buildVatSummary(Order order) {
        Map<BigDecimal, BigDecimal[]> map = new LinkedHashMap<>();
        for (OrderLine line : order.getLines()) {
            BigDecimal rate = line.getVatRate() != null ? line.getVatRate() : BigDecimal.ZERO;
            BigDecimal ht   = line.getTotalHT()  != null ? line.getTotalHT()  : BigDecimal.ZERO;
            BigDecimal tva  = line.getTotalTVA() != null ? line.getTotalTVA() : BigDecimal.ZERO;
            map.merge(rate, new BigDecimal[]{ht, tva},
                    (a, b) -> new BigDecimal[]{a[0].add(b[0]), a[1].add(b[1])});
        }
        List<Map<String, Object>> result = new ArrayList<>();
        map.forEach((rate, vals) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rate",   rate);
            row.put("baseHT", vals[0]);
            row.put("tva",    vals[1]);
            row.put("ttc",    vals[0].add(vals[1]));
            result.add(row);
        });
        return result;
    }
}
