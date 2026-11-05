package com.logicielpourtous.site.features.espace;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.request.RequestRepository;
import com.logicielpourtous.site.features.request.RequestStatus;
import com.logicielpourtous.site.features.shop.ShopOrder;
import com.logicielpourtous.site.features.shop.ShopOrderRepository;
import com.logicielpourtous.site.features.subscription.SubscriptionRepository;
import com.logicielpourtous.site.features.user.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/espace")
public class EspaceController {

    private static final List<RequestStatus> OPEN_STATUSES =
            List.of(RequestStatus.NEW, RequestStatus.IN_PROGRESS);

    private final EspaceContextService   ctx;
    private final RequestRepository      requestRepository;
    private final ShopOrderRepository    shopOrderRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository         userRepository;

    public EspaceController(EspaceContextService ctx,
                            RequestRepository requestRepository,
                            ShopOrderRepository shopOrderRepository,
                            SubscriptionRepository subscriptionRepository,
                            UserRepository userRepository) {
        this.ctx                   = ctx;
        this.requestRepository     = requestRepository;
        this.shopOrderRepository   = shopOrderRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.userRepository        = userRepository;
    }

    @GetMapping
    public String dashboard(@AuthenticationPrincipal CustomUserDetails user,
                            HttpSession session, Model model) {
        if (user == null) return "redirect:/login";
        boolean isGestionnaire = user.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_GESTIONNAIRE"));

        try {
            Customer customer = ctx.loadCustomer(user, session);
            Long customerId = customer.getId();

            long openTickets = requestRepository
                    .countByCustomerIdAndStatusIn(customerId, OPEN_STATUSES);
            long activeSubs  = subscriptionRepository
                    .findByCustomerIdAndActiveTrueOrderByStartDateDesc(customerId).size();

            model.addAttribute("customer",        customer);
            model.addAttribute("openTickets",      openTickets);
            model.addAttribute("activeSubsCount",  activeSubs);

            model.addAttribute("recentTickets",
                    requestRepository.findByCustomerIdOrderByCreatedAtDesc(customerId)
                            .stream()
                            .filter(r -> OPEN_STATUSES.contains(r.getStatus()))
                            .limit(5).toList());

            if (isGestionnaire) {
                Map<Long, ShopOrder> seen = new LinkedHashMap<>();
                shopOrderRepository.findByCustomer_IdOrderByCreatedAtDesc(customerId)
                        .stream()
                        .filter(o -> o.getStatus() == ShopOrder.OrderStatus.PENDING_VALIDATION)
                        .forEach(o -> seen.put(o.getId(), o));
                shopOrderRepository.findByEmailOrderByCreatedAtDesc(user.getUsername())
                        .stream()
                        .filter(o -> o.getStatus() == ShopOrder.OrderStatus.PENDING_VALIDATION)
                        .forEach(o -> seen.putIfAbsent(o.getId(), o));

                model.addAttribute("pendingOrders", List.copyOf(seen.values()));
                model.addAttribute("teamCount",
                        userRepository.findByCustomerId(customerId).size());
            }
        } catch (PortalNotLinkedException ignored) {}

        return isGestionnaire ? "espace/dashboard-gestionnaire" : "espace/dashboard-client";
    }

    @GetMapping("/accounts")
    public String accounts(@AuthenticationPrincipal CustomUserDetails userDetails,
                           HttpSession session, Model model) {
        model.addAttribute("customer", ctx.loadCustomer(userDetails, session));
        return "espace/accounts";
    }

    @GetMapping("/invoices")
    public String invoices(@AuthenticationPrincipal CustomUserDetails userDetails,
                           HttpSession session, Model model) {
        model.addAttribute("customer", ctx.loadCustomer(userDetails, session));
        return "espace/invoices";
    }

    // ── Sélecteur de client (admin / user interne) ────────────────────────────

    @PostMapping("/switch-client")
    public String switchClient(@RequestParam Long clientId, HttpSession session) {
        session.setAttribute("portalViewClientId", clientId);
        return "redirect:/espace";
    }

    @PostMapping("/exit-client")
    public String exitClient(HttpSession session) {
        session.removeAttribute("portalViewClientId");
        return "redirect:/espace";
    }
}
