package com.logicielpourtous.site.features.api.angular;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.invoice.Invoice;
import com.logicielpourtous.site.features.invoice.InvoiceRepository;
import com.logicielpourtous.site.features.request.Request;
import com.logicielpourtous.site.features.request.RequestRepository;
import com.logicielpourtous.site.features.request.RequestStatus;
import com.logicielpourtous.site.features.shop.ShopOrder;
import com.logicielpourtous.site.features.shop.ShopOrderRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/espace")
public class EspaceApiController {

    private static final List<RequestStatus> OPEN_STATUSES =
            List.of(RequestStatus.NEW, RequestStatus.IN_PROGRESS);

    private final RequestRepository   requestRepository;
    private final InvoiceRepository   invoiceRepository;
    private final ShopOrderRepository shopOrderRepository;

    public EspaceApiController(RequestRepository requestRepository,
                               InvoiceRepository invoiceRepository,
                               ShopOrderRepository shopOrderRepository) {
        this.requestRepository   = requestRepository;
        this.invoiceRepository   = invoiceRepository;
        this.shopOrderRepository = shopOrderRepository;
    }

    // ── Tableau de bord ───────────────────────────────────────────────────────

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(@AuthenticationPrincipal CustomUserDetails principal) {
        Long customerId = principal.getCustomerId();

        long openTickets       = customerId != null
                ? requestRepository.countByCustomerIdAndStatusIn(customerId, OPEN_STATUSES) : 0;
        long unpaidInvoices    = customerId != null
                ? invoiceRepository.findByCustomerIdOrderByInvoiceDateDesc(customerId).stream()
                    .filter(i -> i.getStatus() == Invoice.InvoiceStatus.SENT
                              || i.getStatus() == Invoice.InvoiceStatus.OVERDUE)
                    .count() : 0;
        long pendingOrders     = customerId != null
                ? shopOrderRepository.findByCustomer_IdOrderByCreatedAtDesc(customerId).stream()
                    .filter(o -> o.getStatus() == ShopOrder.OrderStatus.PENDING_VALIDATION
                              || o.getStatus() == ShopOrder.OrderStatus.PENDING_PAYMENT)
                    .count() : 0;

        List<Map<String, Object>> recentTickets = customerId != null
                ? requestRepository.findByCustomerIdOrderByCreatedAtDesc(customerId)
                    .stream().limit(5).map(this::toTicketSummary).collect(Collectors.toList())
                : List.of();

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("openTickets",    openTickets);
        res.put("unpaidInvoices", unpaidInvoices);
        res.put("pendingOrders",  pendingOrders);
        res.put("recentTickets",  recentTickets);
        return res;
    }

    // ── Tickets ───────────────────────────────────────────────────────────────

    @GetMapping("/tickets")
    public List<Map<String, Object>> tickets(
            @AuthenticationPrincipal CustomUserDetails principal,
            @RequestParam(required = false) String status) {
        Long customerId = principal.getCustomerId();
        if (customerId == null) return List.of();

        return requestRepository.findByCustomerIdOrderByCreatedAtDesc(customerId).stream()
                .filter(r -> status == null || status.isBlank() || r.getStatus().name().equals(status))
                .map(this::toTicketSummary)
                .collect(Collectors.toList());
    }

    // ── Factures ──────────────────────────────────────────────────────────────

    @GetMapping("/invoices")
    public List<Map<String, Object>> invoices(@AuthenticationPrincipal CustomUserDetails principal) {
        Long customerId = principal.getCustomerId();
        if (customerId == null) return List.of();

        return invoiceRepository.findByCustomerIdOrderByInvoiceDateDesc(customerId).stream()
                .map(i -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",          i.getId());
                    m.put("number",      i.getInvoiceNumber());
                    m.put("date",        i.getInvoiceDate() != null ? i.getInvoiceDate().toString() : null);
                    m.put("totalTTC",    i.getTotalTTC());
                    m.put("status",      i.getStatus().name());
                    m.put("statusLabel", i.getStatus().label);
                    return m;
                })
                .collect(Collectors.toList());
    }

    // ── Commandes ─────────────────────────────────────────────────────────────

    @GetMapping("/orders")
    public List<Map<String, Object>> orders(@AuthenticationPrincipal CustomUserDetails principal) {
        Long customerId = principal.getCustomerId();
        if (customerId == null) return List.of();

        return shopOrderRepository.findByCustomer_IdOrderByCreatedAtDesc(customerId).stream()
                .map(o -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",          o.getId());
                    m.put("reference",   o.getReference());
                    m.put("createdAt",   o.getCreatedAt() != null ? o.getCreatedAt().toString() : null);
                    m.put("totalTTC",    o.getTotalTTC());
                    m.put("status",      o.getStatus().name());
                    m.put("statusLabel", o.getStatus().label);
                    return m;
                })
                .collect(Collectors.toList());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> toTicketSummary(Request r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",          r.getId());
        m.put("reference",   "#" + r.getId());
        m.put("title",       r.getTitle());
        m.put("status",      r.getStatus().name());
        m.put("statusLabel", r.getStatus().getLabel());
        m.put("priority",    r.getPriority() != null ? r.getPriority().name() : null);
        m.put("createdAt",   r.getCreatedAt() != null ? r.getCreatedAt().toString() : null);
        return m;
    }
}
