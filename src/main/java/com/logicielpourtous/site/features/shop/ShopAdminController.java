package com.logicielpourtous.site.features.shop;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.product.ProductRepository;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;

@Controller
@RequestMapping("/intra/shop")
@Secured({"ROLE_ADMIN", "ROLE_COMMERCIAL"})
public class ShopAdminController {

    private final ShopProductRepository      shopProductRepository;
    private final ShopOrderRepository        shopOrderRepository;
    private final ShopVendorRepository       shopVendorRepository;
    private final ProductRepository          productRepository;
    private final CustomerRepository         customerRepository;
    private final ShopConfigService          shopConfigService;
    private final ShopOrderConversionService conversionService;

    public ShopAdminController(ShopProductRepository shopProductRepository,
                               ShopOrderRepository shopOrderRepository,
                               ShopVendorRepository shopVendorRepository,
                               ProductRepository productRepository,
                               CustomerRepository customerRepository,
                               ShopConfigService shopConfigService,
                               ShopOrderConversionService conversionService) {
        this.shopProductRepository = shopProductRepository;
        this.shopOrderRepository   = shopOrderRepository;
        this.shopVendorRepository  = shopVendorRepository;
        this.productRepository     = productRepository;
        this.customerRepository    = customerRepository;
        this.shopConfigService     = shopConfigService;
        this.conversionService     = conversionService;
    }

    // ── Produits ──────────────────────────────────────────────────────────────

    @GetMapping("/products")
    public String products(Model model) {
        model.addAttribute("shopProducts", shopProductRepository.findAllWithProduct());
        model.addAttribute("products",     productRepository.findAll());
        model.addAttribute("vendors",      shopVendorRepository.findAll());
        return "intra/shop/products";
    }

    @PostMapping("/products/create")
    public String createProduct(@RequestParam Long productId,
                                @RequestParam(required = false) String shopTitle,
                                @RequestParam(required = false) String shopDescription,
                                @RequestParam(required = false) String slug,
                                @RequestParam(required = false) String seoTitle,
                                @RequestParam(required = false) String seoDescription,
                                @RequestParam(required = false) BigDecimal priceOverride,
                                @RequestParam(required = false) BigDecimal taxRateOverride,
                                @RequestParam(defaultValue = "0") int displayOrder,
                                @RequestParam(defaultValue = "false") boolean stockEnabled,
                                @RequestParam(defaultValue = "0") int stock,
                                @RequestParam(required = false) Long vendorId,
                                @RequestParam(required = false) String brand,
                                @RequestParam(required = false) String shopCategory,
                                RedirectAttributes ra) {
        if (shopProductRepository.existsByProductId(productId)) {
            ra.addFlashAttribute("errorMessage", "Ce produit est déjà référencé en boutique.");
            return "redirect:/intra/shop/products";
        }
        ShopProduct sp = new ShopProduct();
        sp.setProduct(productRepository.getReferenceById(productId));
        applyFields(sp, shopTitle, shopDescription, slug, seoTitle, seoDescription,
                priceOverride, taxRateOverride, displayOrder, stockEnabled, stock, vendorId, brand, shopCategory);
        shopProductRepository.save(sp);
        ra.addFlashAttribute("successMessage", "Produit ajouté à la boutique.");
        return "redirect:/intra/shop/products";
    }

    @PostMapping("/products/{id}/update")
    public String updateProduct(@PathVariable Long id,
                                @RequestParam(required = false) String shopTitle,
                                @RequestParam(required = false) String shopDescription,
                                @RequestParam(required = false) String slug,
                                @RequestParam(required = false) String seoTitle,
                                @RequestParam(required = false) String seoDescription,
                                @RequestParam(required = false) BigDecimal priceOverride,
                                @RequestParam(required = false) BigDecimal taxRateOverride,
                                @RequestParam(defaultValue = "0") int displayOrder,
                                @RequestParam(defaultValue = "false") boolean stockEnabled,
                                @RequestParam(defaultValue = "0") int stock,
                                @RequestParam(required = false) Long vendorId,
                                @RequestParam(required = false) String brand,
                                @RequestParam(required = false) String shopCategory,
                                RedirectAttributes ra) {
        ShopProduct sp = shopProductRepository.findById(id).orElse(null);
        if (sp == null) return "redirect:/intra/shop/products";
        applyFields(sp, shopTitle, shopDescription, slug, seoTitle, seoDescription,
                priceOverride, taxRateOverride, displayOrder, stockEnabled, stock, vendorId, brand, shopCategory);
        shopProductRepository.save(sp);
        ra.addFlashAttribute("successMessage", "Produit mis à jour.");
        return "redirect:/intra/shop/products";
    }

    @PostMapping("/products/{id}/toggle")
    public String togglePublish(@PathVariable Long id, RedirectAttributes ra) {
        ShopProduct sp = shopProductRepository.findById(id).orElse(null);
        if (sp != null) {
            sp.setPublished(!sp.isPublished());
            shopProductRepository.save(sp);
            ra.addFlashAttribute("successMessage",
                    sp.isPublished() ? "Produit publié en boutique." : "Produit retiré de la boutique.");
        }
        return "redirect:/intra/shop/products";
    }

    @PostMapping("/products/{id}/delete")
    public String deleteProduct(@PathVariable Long id, RedirectAttributes ra) {
        shopProductRepository.deleteById(id);
        ra.addFlashAttribute("successMessage", "Produit supprimé de la boutique.");
        return "redirect:/intra/shop/products";
    }

    // ── Commandes ─────────────────────────────────────────────────────────────

    @GetMapping("/orders")
    public String orders(@RequestParam(required = false) String status) {
        if (status != null && !status.isBlank()) {
            return "redirect:/intra/orders?tab=shop&shopStatus=" + status;
        }
        return "redirect:/intra/orders?tab=shop";
    }

    @GetMapping("/orders/{id}")
    public String orderDetail(@PathVariable Long id) {
        ShopOrder shopOrder = shopOrderRepository.findById(id).orElse(null);
        if (shopOrder == null) return "redirect:/intra/orders?tab=shop";
        // Crée l'Order interne si elle n'existe pas encore, puis redirige
        var internalOrder = conversionService.convertToInternalOrder(shopOrder);
        return "redirect:/intra/orders/" + internalOrder.getId();
    }

    @PostMapping("/orders/{id}/update")
    public String updateOrder(@PathVariable Long id,
                              @RequestParam String customerName,
                              @RequestParam(required = false) String phone,
                              @RequestParam(required = false) String shippingAddress,
                              @RequestParam(required = false) String shippingCity,
                              @RequestParam(required = false) String shippingZip,
                              @RequestParam(defaultValue = "France") String shippingCountry,
                              @RequestParam(required = false) String notes,
                              @RequestParam String paymentMethod,
                              RedirectAttributes ra) {
        ShopOrder order = shopOrderRepository.findById(id).orElse(null);
        if (order == null) return "redirect:/intra/shop/orders";
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
        return "redirect:/intra/shop/orders/" + id;
    }

    @PostMapping("/orders/{id}/status")
    public String updateStatus(@PathVariable Long id,
                               @RequestParam String status,
                               RedirectAttributes ra) {
        ShopOrder order = shopOrderRepository.findById(id).orElse(null);
        if (order != null) {
            order.setStatus(ShopOrder.OrderStatus.valueOf(status));
            shopOrderRepository.save(order);
            ra.addFlashAttribute("successMessage", "Statut mis à jour.");
        }
        return "redirect:/intra/shop/orders/" + id;
    }

    @PostMapping("/orders/{id}/validate")
    public String validateOrder(@PathVariable Long id, RedirectAttributes ra) {
        ShopOrder order = shopOrderRepository.findById(id).orElse(null);
        if (order == null || order.getStatus() != ShopOrder.OrderStatus.PENDING_VALIDATION) {
            ra.addFlashAttribute("errorMessage", "Commande introuvable ou déjà traitée.");
            return "redirect:/intra/shop/orders";
        }
        if (order.getPaymentMethod() == ShopOrder.PaymentMethod.BANK_TRANSFER) {
            order.setBankRef("VIR-" + order.getReference());
            order.setStatus(ShopOrder.OrderStatus.AWAITING_TRANSFER);
        } else {
            order.setStatus(ShopOrder.OrderStatus.PENDING_PAYMENT);
        }
        shopOrderRepository.save(order);
        // Crée l'Order interne pour que l'ESN puisse gérer cette commande
        var internalOrder = conversionService.convertToInternalOrder(order);
        ra.addFlashAttribute("successMessage", "Commande validée — " + order.getStatus().label + ".");
        return "redirect:/intra/orders/" + internalOrder.getId();
    }

    // ── Vendeurs (ESN) ────────────────────────────────────────────────────────

    @GetMapping("/vendors")
    public String vendors(Model model) {
        model.addAttribute("vendors",   shopVendorRepository.findAll());
        model.addAttribute("customers", customerRepository.findAll());
        return "intra/shop/vendors";
    }

    @PostMapping("/vendors/create")
    public String createVendor(@RequestParam Long customerId,
                               @RequestParam String vendorName,
                               @RequestParam(required = false) String vendorDescription,
                               RedirectAttributes ra) {
        if (shopVendorRepository.findByCustomerId(customerId).isPresent()) {
            ra.addFlashAttribute("errorMessage", "Ce client est déjà vendeur.");
            return "redirect:/intra/shop/vendors";
        }
        ShopVendor v = new ShopVendor();
        v.setCustomer(customerRepository.getReferenceById(customerId));
        v.setVendorName(vendorName);
        v.setVendorDescription(vendorDescription);
        shopVendorRepository.save(v);
        ra.addFlashAttribute("successMessage", "Vendeur créé. Clé API : " + v.getApiKey());
        return "redirect:/intra/shop/vendors";
    }

    @PostMapping("/vendors/{id}/regenerate-key")
    public String regenerateKey(@PathVariable Long id, RedirectAttributes ra) {
        ShopVendor v = shopVendorRepository.findById(id).orElse(null);
        if (v != null) {
            v.regenerateApiKey();
            shopVendorRepository.save(v);
            ra.addFlashAttribute("successMessage", "Nouvelle clé API : " + v.getApiKey());
        }
        return "redirect:/intra/shop/vendors";
    }

    @PostMapping("/vendors/{id}/toggle")
    public String toggleVendor(@PathVariable Long id, RedirectAttributes ra) {
        ShopVendor v = shopVendorRepository.findById(id).orElse(null);
        if (v != null) {
            v.setActive(!v.isActive());
            shopVendorRepository.save(v);
        }
        return "redirect:/intra/shop/vendors";
    }

    // ── Paramètres boutique ───────────────────────────────────────────────────

    @GetMapping("/settings")
    @Secured("ROLE_ADMIN")
    public String settings(Model model) {
        model.addAttribute("shopConfig", shopConfigService.getOrCreate());
        return "intra/shop/settings";
    }

    @PostMapping("/settings")
    @Secured("ROLE_ADMIN")
    public String saveSettings(@RequestParam(required = false) String shopName,
                               @RequestParam(required = false) String baseUrl,
                               RedirectAttributes ra) {
        shopConfigService.save(shopName, baseUrl);
        ra.addFlashAttribute("successMessage", "Paramètres boutique enregistrés.");
        return "redirect:/intra/shop/settings";
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void applyFields(ShopProduct sp, String shopTitle, String shopDescription,
                             String slug, String seoTitle, String seoDescription,
                             BigDecimal priceOverride, BigDecimal taxRateOverride,
                             int displayOrder, boolean stockEnabled, int stock, Long vendorId,
                             String brand, String shopCategory) {
        sp.setShopTitle(shopTitle);
        sp.setShopDescription(shopDescription);
        String finalSlug = (slug != null && !slug.isBlank())
                ? slug : ShopProduct.slugify(shopTitle != null ? shopTitle : sp.getProduct().getName());
        sp.setSlug(finalSlug);
        sp.setSeoTitle(seoTitle);
        sp.setSeoDescription(seoDescription);
        sp.setPriceOverride(priceOverride);
        sp.setTaxRateOverride(taxRateOverride);
        sp.setDisplayOrder(displayOrder);
        sp.setStockEnabled(stockEnabled);
        sp.setStock(stock);
        sp.setVendor(vendorId != null ? customerRepository.getReferenceById(vendorId) : null);
        sp.setBrand(brand != null && !brand.isBlank() ? brand.trim() : null);
        sp.setShopCategory(shopCategory != null && !shopCategory.isBlank() ? shopCategory.trim() : null);
    }
}
