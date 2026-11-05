package com.logicielpourtous.site.features.shop;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.supplier.SupplierProduct;
import com.logicielpourtous.site.features.supplier.SupplierProductRepository;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.logicielpourtous.site.features.payment.PaymentConfig;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import jakarta.persistence.criteria.JoinType;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

@Controller
public class PublicShopController {

    private static final Logger log = LoggerFactory.getLogger(PublicShopController.class);
    private static final String CART_KEY = "shopCart";

    private final ShopProductRepository      shopProductRepository;
    private final ShopOrderRepository        shopOrderRepository;
    private final CustomerRepository         customerRepository;
    private final StripeService              stripeService;
    private final ShopConfigService          shopConfigService;
    private final ShopOrderConversionService conversionService;
    private final SupplierProductRepository  supplierProductRepository;

    public PublicShopController(ShopProductRepository shopProductRepository,
                                ShopOrderRepository shopOrderRepository,
                                CustomerRepository customerRepository,
                                StripeService stripeService,
                                ShopConfigService shopConfigService,
                                ShopOrderConversionService conversionService,
                                SupplierProductRepository supplierProductRepository) {
        this.shopProductRepository  = shopProductRepository;
        this.shopOrderRepository    = shopOrderRepository;
        this.customerRepository     = customerRepository;
        this.stripeService          = stripeService;
        this.shopConfigService      = shopConfigService;
        this.supplierProductRepository = supplierProductRepository;
        this.conversionService     = conversionService;
    }

    // ── Compteur panier (injecté dans tous les modèles boutique) ─────────────

    @ModelAttribute("cartCount")
    public int cartCount(HttpSession session) {
        return getCart(session).stream().mapToInt(i -> i.quantity).sum();
    }

    @ModelAttribute("shopName")
    public String shopName() { return shopConfigService.getShopName(); }

    // ── Catalogue ─────────────────────────────────────────────────────────────

    @GetMapping("/boutique")
    @Transactional(readOnly = true)
    public String catalog(@RequestParam(defaultValue = "0") int page,
                          @RequestParam(required = false) List<String> brand,
                          @RequestParam(required = false) List<String> category,
                          @RequestParam(required = false) String q,
                          @RequestParam(required = false) BigDecimal priceMin,
                          @RequestParam(required = false) BigDecimal priceMax,
                          @RequestParam(required = false) String stock,
                          Model model) {
        List<String> brandList    = (brand    != null) ? brand.stream().filter(s -> !s.isBlank()).toList() : List.of();
        List<String> categoryList = (category != null) ? category.stream().filter(s -> !s.isBlank()).toList() : List.of();
        String qFilter            = (q     != null && !q.isBlank())     ? q.trim()     : null;
        String stockFilter        = (stock != null && !stock.isBlank()) ? stock.trim() : null;

        Specification<ShopProduct> spec = buildShopSpec(qFilter, brandList, categoryList, priceMin, priceMax, stockFilter);
        Page<ShopProduct> productPage = shopProductRepository.findAll(spec,
                PageRequest.of(page, 24, Sort.by("displayOrder").ascending().and(Sort.by("id").ascending())));

        boolean hasFilters = !brandList.isEmpty() || !categoryList.isEmpty() || qFilter != null
                          || priceMin != null || priceMax != null || stockFilter != null;

        model.addAttribute("productPage",       productPage);
        model.addAttribute("products",          productPage.getContent());
        model.addAttribute("brands",            shopProductRepository.findDistinctBrands());
        model.addAttribute("categories",        shopProductRepository.findDistinctCategories());
        model.addAttribute("currentBrands",     brandList);
        model.addAttribute("currentCategories", categoryList);
        model.addAttribute("q",                 qFilter != null ? qFilter : "");
        model.addAttribute("priceMin",          priceMin);
        model.addAttribute("priceMax",          priceMax);
        model.addAttribute("stockFilter",       stockFilter != null ? stockFilter : "");
        model.addAttribute("hasFilters",        hasFilters);
        model.addAttribute("pageTitle",         shopConfigService.getShopName());
        model.addAttribute("metaDesc",          "Découvrez notre catalogue de produits et services informatiques.");
        model.addAttribute("canonical",         shopConfigService.getBaseUrl() + "/boutique");
        return "boutique/catalog";
    }

    private Specification<ShopProduct> buildShopSpec(String q, List<String> brands, List<String> categories,
                                                      BigDecimal priceMin, BigDecimal priceMax, String stockFilter) {
        return (root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(cb.equal(root.get("published"), true));

            boolean needProduct = (q != null) || priceMin != null || priceMax != null;
            jakarta.persistence.criteria.Join<Object, Object> productJoin =
                    needProduct ? root.join("product", JoinType.LEFT) : null;

            if (q != null) {
                String like = "%" + q.toLowerCase() + "%";
                predicates.add(cb.or(
                    cb.like(cb.lower(productJoin.get("name")), like),
                    cb.like(cb.lower(root.get("shopTitle")), like),
                    cb.like(cb.lower(root.get("brand")), like),
                    cb.like(cb.lower(root.get("shopCategory")), like)
                ));
            }

            if (!brands.isEmpty())     predicates.add(root.get("brand").in(brands));
            if (!categories.isEmpty()) predicates.add(root.get("shopCategory").in(categories));

            if (priceMin != null || priceMax != null) {
                if (productJoin == null) productJoin = root.join("product", JoinType.LEFT);
                jakarta.persistence.criteria.Expression<BigDecimal> effPrice =
                        cb.coalesce(root.<BigDecimal>get("priceOverride"), productJoin.<BigDecimal>get("priceHT"));
                if (priceMin != null) predicates.add(cb.ge(effPrice, priceMin));
                if (priceMax != null) predicates.add(cb.le(effPrice, priceMax));
            }

            if ("dispo".equals(stockFilter)) {
                predicates.add(cb.or(cb.isFalse(root.get("stockEnabled")), cb.gt(root.get("stock"), 0)));
            } else if ("stock".equals(stockFilter)) {
                predicates.add(cb.isTrue(root.get("stockEnabled")));
                predicates.add(cb.gt(root.get("stock"), 0));
            } else if ("rupture".equals(stockFilter)) {
                predicates.add(cb.isTrue(root.get("stockEnabled")));
                predicates.add(cb.equal(root.get("stock"), 0));
            }

            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    // ── Fiche produit ─────────────────────────────────────────────────────────

    @GetMapping("/boutique/{slug}")
    public String product(@PathVariable String slug, Model model) {
        ShopProduct sp = shopProductRepository.findBySlug(slug)
                .filter(ShopProduct::isPublished)
                .orElse(null);
        if (sp == null) return "redirect:/boutique";

        String seoTitle = sp.getSeoTitle() != null ? sp.getSeoTitle() : sp.getDisplayTitle();
        String seoDesc  = sp.getSeoDescription() != null ? sp.getSeoDescription()
                : (sp.getShopDescription() != null ? sp.getShopDescription().replaceAll("<[^>]+>","").substring(0, Math.min(160, sp.getShopDescription().length())) : "");

        // JSON-LD Product schema
        String jsonLd = "{\"@context\":\"https://schema.org\",\"@type\":\"Product\","
                + "\"name\":\"" + escapeJson(sp.getDisplayTitle()) + "\","
                + "\"description\":\"" + escapeJson(seoDesc) + "\","
                + "\"offers\":{\"@type\":\"Offer\",\"price\":\"" + sp.getPriceTTC() + "\","
                + "\"priceCurrency\":\"EUR\",\"availability\":\"" + (sp.isAvailable() ? "https://schema.org/InStock" : "https://schema.org/OutOfStock") + "\"}"
                + "}";

        // EAN : upcCode fournisseur > vendorPartNumber > reference produit
        String ean = null;
        if (sp.getProduct() != null) {
            List<SupplierProduct> sps = supplierProductRepository
                    .findByProductIdOrderBySupplierId(sp.getProduct().getId());
            ean = sps.stream().map(SupplierProduct::getUpcCode)
                    .filter(c -> c != null && !c.isBlank()).findFirst()
                    .orElseGet(() -> sps.stream().map(SupplierProduct::getVendorPartNumber)
                            .filter(c -> c != null && !c.isBlank()).findFirst()
                            .orElseGet(() -> sp.getProduct().getReference()));
        }

        String baseUrl = shopConfigService.getBaseUrl();
        model.addAttribute("product",   sp);
        model.addAttribute("ean",       ean);
        model.addAttribute("pageTitle", seoTitle + " | " + shopConfigService.getShopName());
        model.addAttribute("metaDesc",  seoDesc);
        model.addAttribute("canonical", baseUrl + "/boutique/" + slug);
        model.addAttribute("jsonLd",    jsonLd);
        if (sp.getFeaturedImagePath() != null)
            model.addAttribute("ogImage", baseUrl + "/uploads/" + sp.getFeaturedImagePath());
        return "boutique/product";
    }

    // ── Panier ────────────────────────────────────────────────────────────────

    @PostMapping("/boutique/cart/add")
    public String addToCart(@RequestParam Long shopProductId,
                            @RequestParam(defaultValue = "1") int quantity,
                            HttpSession session,
                            RedirectAttributes ra) {
        ShopProduct sp = shopProductRepository.findById(shopProductId).orElse(null);
        if (sp == null || !sp.isAvailable()) {
            ra.addFlashAttribute("errorMessage", "Produit indisponible.");
            return "redirect:/boutique";
        }
        if (sp.isStockEnabled() && sp.getStock() < quantity) {
            ra.addFlashAttribute("errorMessage", "Stock insuffisant.");
            return "redirect:/boutique/" + sp.getSlug();
        }

        List<CartItem> cart = getCart(session);
        cart.stream().filter(i -> i.shopProductId.equals(shopProductId)).findFirst()
                .ifPresentOrElse(
                        i -> i.quantity += quantity,
                        () -> cart.add(new CartItem(sp, quantity))
                );
        session.setAttribute(CART_KEY, cart);
        ra.addFlashAttribute("successMessage", "Ajouté au panier.");
        return "redirect:/boutique/" + sp.getSlug();
    }

    @GetMapping("/boutique/panier")
    public String cart(HttpSession session, Model model) {
        List<CartItem> cart = getCart(session);
        model.addAttribute("cart",      cart);
        model.addAttribute("totals",    computeTotals(cart));
        model.addAttribute("pageTitle", "Mon panier | " + shopConfigService.getShopName());
        return "boutique/cart";
    }

    @PostMapping("/boutique/cart/update")
    public String updateCart(@RequestParam Long shopProductId,
                              @RequestParam int quantity,
                              HttpSession session) {
        List<CartItem> cart = getCart(session);
        if (quantity <= 0) {
            cart.removeIf(i -> i.shopProductId.equals(shopProductId));
        } else {
            cart.stream().filter(i -> i.shopProductId.equals(shopProductId))
                    .findFirst().ifPresent(i -> i.quantity = quantity);
        }
        session.setAttribute(CART_KEY, cart);
        return "redirect:/boutique/panier";
    }

    @PostMapping("/boutique/cart/remove")
    public String removeFromCart(@RequestParam Long shopProductId, HttpSession session) {
        List<CartItem> cart = getCart(session);
        cart.removeIf(i -> i.shopProductId.equals(shopProductId));
        session.setAttribute(CART_KEY, cart);
        return "redirect:/boutique/panier";
    }

    // ── Commande / Checkout ───────────────────────────────────────────────────

    @GetMapping("/boutique/commande")
    public String checkout(HttpSession session,
                           @AuthenticationPrincipal CustomUserDetails principal,
                           Model model) {
        List<CartItem> cart = getCart(session);
        if (cart.isEmpty()) return "redirect:/boutique/panier";
        if (principal == null) return "redirect:/login?redirect=/boutique/commande";

        // Pré-remplir depuis le profil client lié
        Customer customer = null;
        if (principal.getCustomerId() != null) {
            customer = customerRepository.findById(principal.getCustomerId()).orElse(null);
        }
        if (customer == null) {
            customer = customerRepository.findByEmail(principal.getUsername()).orElse(null);
        }

        model.addAttribute("cart",            cart);
        model.addAttribute("totals",          computeTotals(cart));
        model.addAttribute("pageTitle",       "Demande de commande | " + shopConfigService.getShopName());
        model.addAttribute("stripeEnabled",   stripeService.isConfigured());
        model.addAttribute("customer",        customer);
        model.addAttribute("isGestionnaire",  principal.isGestionnaire());
        return "boutique/checkout";
    }

    @PostMapping("/boutique/commande")
    public String processCheckout(@RequestParam String paymentMethod,
                                  @RequestParam String customerName,
                                  @RequestParam(required = false) String phone,
                                  @RequestParam(required = false) String shippingAddress,
                                  @RequestParam(required = false) String shippingCity,
                                  @RequestParam(required = false) String shippingZip,
                                  @RequestParam(defaultValue = "France") String shippingCountry,
                                  @RequestParam(required = false) String notes,
                                  HttpSession session,
                                  @AuthenticationPrincipal CustomUserDetails principal,
                                  RedirectAttributes ra) {
        if (principal == null) return "redirect:/login?redirect=/boutique/commande";

        List<CartItem> cart = getCart(session);
        if (cart.isEmpty()) return "redirect:/boutique/panier";

        Map<String, BigDecimal> totals = computeTotals(cart);

        ShopOrder order = new ShopOrder();
        order.setEmail(principal.getUsername());
        order.setCustomerName(customerName);
        order.setPhone(phone);
        order.setShippingAddress(shippingAddress);
        order.setShippingCity(shippingCity);
        order.setShippingZip(shippingZip);
        order.setShippingCountry(shippingCountry);
        order.setNotes(notes);
        order.setTotalHT(totals.get("totalHT"));
        order.setVatAmount(totals.get("totalVAT"));
        order.setTotalTTC(totals.get("totalTTC"));
        order.setPaymentMethod(ShopOrder.PaymentMethod.valueOf(paymentMethod));

        // Lier au client
        if (principal.getCustomerId() != null) {
            customerRepository.findById(principal.getCustomerId()).ifPresent(order::setCustomer);
        }
        if (order.getCustomer() == null) {
            customerRepository.findByEmail(principal.getUsername()).ifPresent(order::setCustomer);
        }

        // Lignes
        for (CartItem item : cart) {
            ShopOrderLine line = new ShopOrderLine();
            line.setOrder(order);
            line.setShopProduct(shopProductRepository.findById(item.shopProductId).orElse(null));
            line.setProductName(item.productName);
            line.setQuantity(item.quantity);
            line.setUnitPriceHT(item.unitPriceHT);
            line.setTaxRate(item.taxRate);
            line.setLineTotalHT(item.getLineTotalHT());
            line.setLineTotalTTC(item.getLineTotalTTC());
            order.getLines().add(line);
        }

        // Toujours en attente de validation — le gestionnaire déclenche le paiement
        order.setStatus(ShopOrder.OrderStatus.PENDING_VALIDATION);

        shopOrderRepository.save(order);
        session.removeAttribute(CART_KEY);

        return "redirect:/boutique/confirmation/" + order.getReference();
    }

    // ── Paiement Stripe ───────────────────────────────────────────────────────

    @GetMapping("/boutique/paiement/{ref}/stripe")
    public String stripeRedirect(@PathVariable String ref, RedirectAttributes ra) {
        ShopOrder order = shopOrderRepository.findByReference(ref).orElse(null);
        if (order == null) return "redirect:/boutique";

        if (!stripeService.isConfigured()) {
            ra.addFlashAttribute("errorMessage", "Stripe n'est pas configuré. Choisissez le virement.");
            return "redirect:/boutique/confirmation/" + ref;
        }

        try {
            String baseUrl    = shopConfigService.getBaseUrl();
            String successUrl = baseUrl + "/boutique/paiement/" + ref + "/confirm?session_id={CHECKOUT_SESSION_ID}";
            String cancelUrl  = baseUrl + "/boutique/commande";
            String checkoutUrl = stripeService.createCheckoutSession(order, successUrl, cancelUrl);
            order.setStripeSessionId(checkoutUrl); // stocké temporairement
            shopOrderRepository.save(order);
            return "redirect:" + checkoutUrl;
        } catch (Exception e) {
            log.error("Erreur Stripe : {}", e.getMessage());
            ra.addFlashAttribute("errorMessage", "Erreur Stripe : " + e.getMessage());
            return "redirect:/boutique/confirmation/" + ref;
        }
    }

    @GetMapping("/boutique/paiement/{ref}/confirm")
    public String stripeConfirm(@PathVariable String ref,
                                 @RequestParam(name = "session_id", required = false) String sessionId) {
        ShopOrder order = shopOrderRepository.findByReference(ref).orElse(null);
        if (order == null) return "redirect:/boutique";

        if (sessionId != null && stripeService.isSessionPaid(sessionId)) {
            order.setStatus(ShopOrder.OrderStatus.PAID);
            order.setStripeSessionId(sessionId);
            order.setPaidAt(LocalDateTime.now());
            shopOrderRepository.save(order);
            // Crée l'Order interne + la facture marquée PAID
            conversionService.convertAndInvoiceStripeOrder(order);
        }
        return "redirect:/boutique/confirmation/" + ref;
    }

    // ── Confirmation ──────────────────────────────────────────────────────────

    @GetMapping("/boutique/confirmation/{ref}")
    public String confirmation(@PathVariable String ref, Model model) {
        ShopOrder order = shopOrderRepository.findByReference(ref).orElse(null);
        if (order == null) return "redirect:/boutique";
        model.addAttribute("order",     order);
        model.addAttribute("pageTitle", "Confirmation | " + shopConfigService.getShopName());
        return "boutique/confirmation";
    }

    @GetMapping("/boutique/virement/{ref}")
    public String bankTransfer(@PathVariable String ref, Model model) {
        ShopOrder order = shopOrderRepository.findByReference(ref).orElse(null);
        if (order == null) return "redirect:/boutique";
        PaymentConfig bank = shopConfigService.getBankTransferConfig();
        model.addAttribute("order",       order);
        model.addAttribute("bankIban",    bank != null ? bank.getIban() : null);
        model.addAttribute("bankBic",     bank != null ? bank.getBic() : null);
        model.addAttribute("bankBenef",   bank != null ? bank.getBeneficiaryName() : null);
        model.addAttribute("pageTitle",   "Instructions virement | " + shopConfigService.getShopName());
        return "boutique/bank-transfer";
    }

    // ── Utilitaires ───────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<CartItem> getCart(HttpSession session) {
        Object cart = session.getAttribute(CART_KEY);
        if (cart instanceof List) return (List<CartItem>) cart;
        List<CartItem> newCart = new ArrayList<>();
        session.setAttribute(CART_KEY, newCart);
        return newCart;
    }

    private Map<String, BigDecimal> computeTotals(List<CartItem> cart) {
        BigDecimal totalHT  = cart.stream().map(CartItem::getLineTotalHT).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalVAT = cart.stream().map(CartItem::getVatAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalTTC = totalHT.add(totalVAT).setScale(2, RoundingMode.HALF_UP);
        Map<String, BigDecimal> m = new HashMap<>();
        m.put("totalHT",  totalHT.setScale(2, RoundingMode.HALF_UP));
        m.put("totalVAT", totalVAT.setScale(2, RoundingMode.HALF_UP));
        m.put("totalTTC", totalTTC);
        return m;
    }

    private String generateRef() {
        return UUID.randomUUID().toString().replace("-","").substring(0,8).toUpperCase();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");
    }
}
