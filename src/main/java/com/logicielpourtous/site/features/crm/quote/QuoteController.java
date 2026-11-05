package com.logicielpourtous.site.features.crm.quote;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.crm.opportunity.CrmOpportunity;
import com.logicielpourtous.site.features.crm.opportunity.CrmOpportunityRepository;
import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.order.Order;
import com.logicielpourtous.site.features.order.OrderLine;
import com.logicielpourtous.site.features.order.OrderLineRepository;
import com.logicielpourtous.site.features.order.OrderRepository;
import com.logicielpourtous.site.features.product.ProductRepository;
import com.logicielpourtous.site.features.user.UserRepository;
import com.logicielpourtous.site.features.vatrate.VatRateRepository;
import org.springframework.security.access.annotation.Secured;
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
@RequestMapping("/intra/quotes")
@Secured({"ROLE_ADMIN", "ROLE_USER", "ROLE_COMMERCIAL"})
public class QuoteController {

    private final QuoteRepository          quoteRepository;
    private final QuoteLineRepository      quoteLineRepository;
    private final CustomerRepository       customerRepository;
    private final ProductRepository        productRepository;
    private final VatRateRepository        vatRateRepository;
    private final UserRepository           userRepository;
    private final OrderRepository          orderRepository;
    private final OrderLineRepository      orderLineRepository;
    private final CrmOpportunityRepository opportunityRepository;

    public QuoteController(QuoteRepository quoteRepository,
                           QuoteLineRepository quoteLineRepository,
                           CustomerRepository customerRepository,
                           ProductRepository productRepository,
                           VatRateRepository vatRateRepository,
                           UserRepository userRepository,
                           OrderRepository orderRepository,
                           OrderLineRepository orderLineRepository,
                           CrmOpportunityRepository opportunityRepository) {
        this.quoteRepository     = quoteRepository;
        this.quoteLineRepository = quoteLineRepository;
        this.customerRepository  = customerRepository;
        this.productRepository   = productRepository;
        this.vatRateRepository   = vatRateRepository;
        this.userRepository      = userRepository;
        this.orderRepository     = orderRepository;
        this.orderLineRepository = orderLineRepository;
        this.opportunityRepository = opportunityRepository;
    }

    // ── Liste ─────────────────────────────────────────────────────────────────

    @GetMapping
    public String list(@RequestParam(required = false) String status, Model model) {
        List<Quote> quotes = status != null && !status.isBlank()
                ? quoteRepository.findByStatusOrderByCreatedAtDesc(QuoteStatus.valueOf(status))
                : quoteRepository.findAllByOrderByCreatedAtDesc();

        Map<QuoteStatus, Long> counts = new LinkedHashMap<>();
        for (QuoteStatus s : QuoteStatus.values())
            counts.put(s, quoteRepository.countByStatus(s));

        model.addAttribute("quotes",        quotes);
        model.addAttribute("statusCounts",  counts);
        model.addAttribute("statuses",      QuoteStatus.values());
        model.addAttribute("currentStatus", status);
        model.addAttribute("customers",     customerRepository.findAll());
        return "intra/quotes/list";
    }

    // ── Nouveau devis ─────────────────────────────────────────────────────────

    @GetMapping("/new")
    public String newForm(@RequestParam(required = false) Long customerId,
                          @RequestParam(required = false) Long opportunityId,
                          Model model) {
        CrmOpportunity opp = opportunityId != null
                ? opportunityRepository.findById(opportunityId).orElse(null) : null;
        // Si opportunité connue et pas de customerId explicite, prendre le client de l'affaire
        Long resolvedCustomerId = customerId;
        if (resolvedCustomerId == null && opp != null && opp.getCustomer() != null) {
            resolvedCustomerId = opp.getCustomer().getId();
        }
        model.addAttribute("customers",      customerRepository.findAll());
        model.addAttribute("preCustomer",    resolvedCustomerId != null
                ? customerRepository.findById(resolvedCustomerId).orElse(null) : null);
        model.addAttribute("opportunity",    opp);
        return "intra/quotes/form";
    }

    @PostMapping
    public String create(@RequestParam Long customerId,
                         @RequestParam(required = false) Long opportunityId,
                         @RequestParam(required = false) LocalDate quoteDate,
                         @RequestParam(required = false) LocalDate validUntil,
                         @RequestParam(required = false) String notes,
                         @AuthenticationPrincipal CustomUserDetails principal,
                         RedirectAttributes ra) {
        Quote q = new Quote();
        customerRepository.findById(customerId).ifPresent(q::setCustomer);
        if (opportunityId != null) {
            opportunityRepository.findById(opportunityId).ifPresent(q::setOpportunity);
        }
        q.setQuoteDate(quoteDate != null ? quoteDate : LocalDate.now());
        q.setValidUntil(validUntil != null ? validUntil : LocalDate.now().plusDays(30));
        q.setNotes(notes);
        q.setStatus(QuoteStatus.BROUILLON);
        if (principal != null) userRepository.findById(principal.getUserId()).ifPresent(q::setCreatedBy);
        quoteRepository.save(q);
        q.setQuoteNumber("DEV-" + LocalDate.now().getYear() + "-" + q.getId());
        quoteRepository.save(q);
        ra.addFlashAttribute("successMessage", "Devis " + q.getQuoteNumber() + " créé.");
        // Retourner vers l'affaire si on vient d'une affaire
        if (opportunityId != null) return "redirect:/intra/pipeline/" + opportunityId + "#tab-quotes";
        return "redirect:/intra/quotes/" + q.getId();
    }

    // ── Détail ────────────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public String detail(@PathVariable Long id, Model model) {
        Quote q = quoteRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Devis introuvable : " + id));
        q.getLines().size();
        if (q.getCustomer() != null) q.getCustomer().getCompanyName();

        Customer.FiscalZone zone = q.getCustomer() != null
                ? q.getCustomer().getFiscalZone() : Customer.FiscalZone.FRANCE;

        model.addAttribute("quote",      q);
        model.addAttribute("statuses",   QuoteStatus.values());
        model.addAttribute("vatRates",   vatRateRepository.findByZoneAndActiveTrueOrderByRateDesc(zone));
        model.addAttribute("vatSummary", buildVatSummary(q));
        return "intra/quotes/detail";
    }

    // ── Mise à jour ───────────────────────────────────────────────────────────

    @PostMapping("/{id}/update")
    public String update(@PathVariable Long id,
                         @RequestParam(required = false) LocalDate quoteDate,
                         @RequestParam(required = false) LocalDate validUntil,
                         @RequestParam(required = false) String notes,
                         RedirectAttributes ra) {
        Quote q = quoteRepository.findById(id).orElseThrow();
        q.setQuoteDate(quoteDate);
        q.setValidUntil(validUntil);
        q.setNotes(notes);
        quoteRepository.save(q);
        ra.addFlashAttribute("successMessage", "Devis mis à jour.");
        return "redirect:/intra/quotes/" + id;
    }

    // ── Changement de statut ──────────────────────────────────────────────────

    @PostMapping("/{id}/status")
    public String updateStatus(@PathVariable Long id,
                               @RequestParam QuoteStatus status,
                               RedirectAttributes ra) {
        Quote q = quoteRepository.findById(id).orElseThrow();
        q.setStatus(status);
        quoteRepository.save(q);
        ra.addFlashAttribute("successMessage", "Statut : " + status.label + ".");
        return "redirect:/intra/quotes/" + id;
    }

    // ── Lignes ────────────────────────────────────────────────────────────────

    @PostMapping("/{id}/lines")
    @Transactional
    public String addLine(@PathVariable Long id,
                          @RequestParam(required = false) Long productId,
                          @RequestParam String description,
                          @RequestParam(defaultValue = "1") Integer quantity,
                          @RequestParam BigDecimal unitPrice,
                          @RequestParam(required = false) Long vatRateId,
                          @RequestParam(required = false) BigDecimal costPrice,
                          RedirectAttributes ra) {
        Quote q = quoteRepository.findById(id).orElseThrow();
        QuoteLine line = new QuoteLine();
        line.setQuote(q);
        line.setDescription(description);
        line.setQuantity(quantity);
        line.setUnitPrice(unitPrice);
        line.setTotalHT(unitPrice.multiply(BigDecimal.valueOf(quantity)));
        if (productId != null) productRepository.findById(productId).ifPresent(p -> line.setDescription(
                line.getDescription() != null ? line.getDescription() : p.getName()));

        if (vatRateId != null) {
            vatRateRepository.findById(vatRateId).ifPresent(vr -> {
                line.setVatRate(vr.getRate());
                line.setVatRateLabel(vr.getName());
            });
        } else {
            Customer.FiscalZone zone = q.getCustomer() != null
                    ? q.getCustomer().getFiscalZone() : Customer.FiscalZone.FRANCE;
            vatRateRepository.findFirstByZoneAndIsDefaultTrueAndActiveTrue(zone).ifPresent(vr -> {
                line.setVatRate(vr.getRate());
                line.setVatRateLabel(vr.getName());
            });
        }

        if (costPrice != null && costPrice.compareTo(BigDecimal.ZERO) > 0) {
            line.setCostPrice(costPrice);
            line.setTotalCost(costPrice.multiply(BigDecimal.valueOf(quantity)));
        }
        line.setPosition(quoteLineRepository.findByQuoteIdOrderByPositionAsc(id).size() + 1);
        quoteLineRepository.save(line);
        recalcTotal(id);
        ra.addFlashAttribute("successMessage", "Ligne ajoutée.");
        return "redirect:/intra/quotes/" + id;
    }

    @PostMapping("/{id}/lines/{lineId}/update")
    @Transactional
    public String updateLine(@PathVariable Long id,
                             @PathVariable Long lineId,
                             @RequestParam String description,
                             @RequestParam(defaultValue = "1") Integer quantity,
                             @RequestParam BigDecimal unitPrice,
                             @RequestParam(required = false) Long vatRateId,
                             @RequestParam(required = false) BigDecimal costPrice,
                             RedirectAttributes ra) {
        quoteRepository.findById(id).orElseThrow();
        quoteLineRepository.findById(lineId)
                .filter(l -> l.getQuote().getId().equals(id))
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
                    quoteLineRepository.save(line);
                });
        recalcTotal(id);
        ra.addFlashAttribute("successMessage", "Ligne mise à jour.");
        return "redirect:/intra/quotes/" + id;
    }

    @PostMapping("/{id}/lines/{lineId}/delete")
    @Transactional
    public String deleteLine(@PathVariable Long id,
                             @PathVariable Long lineId,
                             RedirectAttributes ra) {
        quoteLineRepository.findById(lineId)
                .filter(l -> l.getQuote().getId().equals(id))
                .ifPresent(quoteLineRepository::delete);
        recalcTotal(id);
        ra.addFlashAttribute("successMessage", "Ligne supprimée.");
        return "redirect:/intra/quotes/" + id;
    }

    // ── Conversion devis → commande ───────────────────────────────────────────

    @PostMapping("/{id}/convert-to-order")
    @Transactional
    public String convertToOrder(@PathVariable Long id, RedirectAttributes ra) {
        Quote q = quoteRepository.findById(id).orElseThrow();
        q.getLines().size();

        Order order = new Order();
        order.setCustomer(q.getCustomer());
        order.setOrderDate(LocalDate.now());
        order.setNotes(q.getNotes());
        order.setStatus(Order.OrderStatus.PENDING);
        order.setTotalAmount(q.getTotalHT());
        order.setTotalTVA(q.getTotalTVA());
        orderRepository.save(order);
        order.setOrderNumber("CMD-" + LocalDate.now().getYear() + "-" + order.getId());
        orderRepository.save(order);

        int pos = 0;
        for (QuoteLine ql : q.getLines()) {
            OrderLine ol = new OrderLine();
            ol.setOrder(order);
            ol.setDescription(ql.getDescription());
            ol.setQuantity(ql.getQuantity());
            ol.setUnitPrice(ql.getUnitPrice());
            ol.setTotalHT(ql.getTotalHT());
            ol.setVatRate(ql.getVatRate());
            ol.setVatRateLabel(ql.getVatRateLabel());
            ol.setTotalTVA(ql.getTotalTVA());
            ol.setCostPrice(ql.getCostPrice());
            ol.setTotalCost(ql.getTotalCost());
            orderLineRepository.save(ol);
            pos++;
        }

        q.setStatus(QuoteStatus.ACCEPTE);
        q.setConvertedOrderId(order.getId());
        quoteRepository.save(q);

        ra.addFlashAttribute("successMessage",
                "Devis converti en commande " + order.getOrderNumber() + ".");
        return "redirect:/intra/orders/" + order.getId();
    }

    // ── Vue imprimable ────────────────────────────────────────────────────────

    @GetMapping("/{id}/print")
    @Transactional(readOnly = true)
    public String print(@PathVariable Long id,
                        @AuthenticationPrincipal CustomUserDetails principal,
                        Model model) {
        Quote q = quoteRepository.findById(id).orElseThrow();
        q.getLines().size();
        if (q.getCustomer() != null) q.getCustomer().getCompanyName();
        model.addAttribute("quote", q);
        if (principal != null) model.addAttribute("tenant", principal.getTenant());
        return "intra/quotes/print";
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void recalcTotal(Long quoteId) {
        List<QuoteLine> lines = quoteLineRepository.findByQuoteIdOrderByPositionAsc(quoteId);
        BigDecimal ht = BigDecimal.ZERO, tva = BigDecimal.ZERO;
        for (QuoteLine line : lines) {
            BigDecimal lineHT  = line.getTotalHT() != null ? line.getTotalHT() : BigDecimal.ZERO;
            BigDecimal rate    = line.getVatRate()  != null ? line.getVatRate() : BigDecimal.ZERO;
            BigDecimal lineTVA = lineHT.multiply(rate.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP))
                    .setScale(2, RoundingMode.HALF_UP);
            line.setTotalTVA(lineTVA);
            ht  = ht.add(lineHT);
            tva = tva.add(lineTVA);
        }
        quoteRepository.updateTotals(quoteId, ht, tva, ht.add(tva));
    }

    private List<Map<String, Object>> buildVatSummary(Quote q) {
        Map<BigDecimal, BigDecimal[]> map = new LinkedHashMap<>();
        for (QuoteLine line : quoteLineRepository.findByQuoteIdOrderByPositionAsc(q.getId())) {
            BigDecimal rate = line.getVatRate()  != null ? line.getVatRate()  : BigDecimal.ZERO;
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
