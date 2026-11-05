package com.logicielpourtous.site.features.espace;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.shop.*;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/espace/orders")
@Secured({"ROLE_GESTIONNAIRE", "ROLE_CLIENT"})
public class EspaceOrderController {

    private final ShopOrderRepository shopOrderRepository;

    public EspaceOrderController(ShopOrderRepository shopOrderRepository) {
        this.shopOrderRepository = shopOrderRepository;
    }

    @GetMapping
    public String list(@AuthenticationPrincipal CustomUserDetails principal, Model model) {
        // Merge by customerId + by email to catch all orders regardless of how they were linked
        Map<Long, ShopOrder> seen = new LinkedHashMap<>();
        if (principal.getCustomerId() != null) {
            shopOrderRepository.findByCustomer_IdOrderByCreatedAtDesc(principal.getCustomerId())
                    .forEach(o -> seen.put(o.getId(), o));
        }
        shopOrderRepository.findByEmailOrderByCreatedAtDesc(principal.getUsername())
                .forEach(o -> seen.putIfAbsent(o.getId(), o));
        List<ShopOrder> orders = new ArrayList<>(seen.values());
        model.addAttribute("orders", orders);
        return "espace/orders";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id,
                         @AuthenticationPrincipal CustomUserDetails principal,
                         Model model) {
        ShopOrder order = findOwned(id, principal);
        if (order == null) return "redirect:/espace/orders";
        model.addAttribute("order",          order);
        model.addAttribute("paymentMethods", ShopOrder.PaymentMethod.values());
        return "espace/order-detail";
    }

    @PostMapping("/{id}/update")
    @Secured("ROLE_GESTIONNAIRE")
    public String update(@PathVariable Long id,
                         @RequestParam String customerName,
                         @RequestParam(required = false) String phone,
                         @RequestParam(required = false) String shippingAddress,
                         @RequestParam(required = false) String shippingCity,
                         @RequestParam(required = false) String shippingZip,
                         @RequestParam(defaultValue = "France") String shippingCountry,
                         @RequestParam(required = false) String notes,
                         @RequestParam String paymentMethod,
                         @AuthenticationPrincipal CustomUserDetails principal,
                         RedirectAttributes ra) {
        ShopOrder order = findOwned(id, principal);
        if (order == null) return "redirect:/espace/orders";
        order.setCustomerName(customerName);
        order.setPhone(phone);
        order.setShippingAddress(shippingAddress);
        order.setShippingCity(shippingCity);
        order.setShippingZip(shippingZip);
        order.setShippingCountry(shippingCountry);
        order.setNotes(notes);
        order.setPaymentMethod(ShopOrder.PaymentMethod.valueOf(paymentMethod));
        shopOrderRepository.save(order);
        ra.addFlashAttribute("successMessage", "Commande mise à jour.");
        return "redirect:/espace/orders/" + id;
    }

    @PostMapping("/{id}/validate")
    @Secured("ROLE_GESTIONNAIRE")
    public String validate(@PathVariable Long id,
                           @AuthenticationPrincipal CustomUserDetails principal,
                           RedirectAttributes ra) {
        ShopOrder order = findOwned(id, principal);
        if (order == null || order.getStatus() != ShopOrder.OrderStatus.PENDING_VALIDATION) {
            ra.addFlashAttribute("errorMessage", "Commande introuvable ou déjà traitée.");
            return "redirect:/espace/orders";
        }
        if (order.getPaymentMethod() == ShopOrder.PaymentMethod.BANK_TRANSFER) {
            order.setBankRef("VIR-" + order.getReference());
            order.setStatus(ShopOrder.OrderStatus.AWAITING_TRANSFER);
        } else {
            order.setStatus(ShopOrder.OrderStatus.PENDING_PAYMENT);
        }
        shopOrderRepository.save(order);
        ra.addFlashAttribute("successMessage", "Commande validée — " + order.getStatus().label + ".");
        return "redirect:/espace/orders/" + id;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ShopOrder findOwned(Long id, CustomUserDetails principal) {
        ShopOrder order = shopOrderRepository.findById(id).orElse(null);
        if (order == null) return null;
        if (principal.getCustomerId() != null
                && order.getCustomer() != null
                && order.getCustomer().getId().equals(principal.getCustomerId())) {
            return order;
        }
        if (order.getEmail() != null && order.getEmail().equalsIgnoreCase(principal.getUsername())) {
            return order;
        }
        return null;
    }
}
