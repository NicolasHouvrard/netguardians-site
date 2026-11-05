package com.logicielpourtous.site.features.shop;

import com.logicielpourtous.site.features.product.ProductRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * API REST pour les vendeurs ESN partenaires.
 * Authentification : header X-API-Key
 */
@RestController
@RequestMapping("/api/v1/shop")
public class ShopApiController {

    private final ShopVendorRepository  shopVendorRepository;
    private final ShopProductRepository shopProductRepository;
    private final ShopOrderRepository   shopOrderRepository;
    private final ProductRepository     productRepository;

    public ShopApiController(ShopVendorRepository shopVendorRepository,
                             ShopProductRepository shopProductRepository,
                             ShopOrderRepository shopOrderRepository,
                             ProductRepository productRepository) {
        this.shopVendorRepository  = shopVendorRepository;
        this.shopProductRepository = shopProductRepository;
        this.shopOrderRepository   = shopOrderRepository;
        this.productRepository     = productRepository;
    }

    // ── Auth helper ───────────────────────────────────────────────────────────

    private ShopVendor resolveVendor(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return null;
        return shopVendorRepository.findByApiKey(apiKey)
                .filter(ShopVendor::isActive)
                .orElse(null);
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Clé API manquante ou invalide."));
    }

    // ── Produits ──────────────────────────────────────────────────────────────

    @GetMapping("/products")
    public ResponseEntity<?> listProducts(@RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        ShopVendor vendor = resolveVendor(apiKey);
        if (vendor == null) return unauthorized();

        List<Map<String, Object>> result = shopProductRepository.findByVendorId(vendor.getId())
                .stream()
                .map(sp -> Map.<String, Object>of(
                        "id",           sp.getId(),
                        "slug",         sp.getSlug(),
                        "title",        sp.getDisplayTitle(),
                        "priceTTC",     sp.getPriceTTC(),
                        "published",    sp.isPublished(),
                        "stockEnabled", sp.isStockEnabled(),
                        "stock",        sp.getStock()
                ))
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @PutMapping("/products/{id}/stock")
    public ResponseEntity<?> updateStock(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                         @PathVariable Long id,
                                         @RequestBody Map<String, Integer> body) {
        ShopVendor vendor = resolveVendor(apiKey);
        if (vendor == null) return unauthorized();

        ShopProduct sp = shopProductRepository.findById(id).orElse(null);
        if (sp == null || !vendor.getId().equals(sp.getVendor() != null ? sp.getVendor().getId() : null)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Produit introuvable ou accès refusé."));
        }
        Integer newStock = body.get("stock");
        if (newStock == null || newStock < 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "stock doit être un entier ≥ 0."));
        }
        sp.setStock(newStock);
        sp.setStockEnabled(true);
        shopProductRepository.save(sp);
        return ResponseEntity.ok(Map.of("id", sp.getId(), "stock", sp.getStock()));
    }

    @PostMapping("/products")
    public ResponseEntity<?> createListing(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                           @RequestBody Map<String, Object> body) {
        ShopVendor vendor = resolveVendor(apiKey);
        if (vendor == null) return unauthorized();

        Long productId = body.containsKey("productId") ? Long.valueOf(body.get("productId").toString()) : null;
        if (productId == null) return ResponseEntity.badRequest().body(Map.of("error", "productId requis."));

        if (shopProductRepository.existsByProductId(productId)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Ce produit est déjà référencé en boutique."));
        }

        ShopProduct sp = new ShopProduct();
        var product = productRepository.findById(productId).orElse(null);
        if (product == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Produit introuvable."));
        }
        sp.setProduct(product);

        sp.setVendor(vendor.getCustomer());
        sp.setShopTitle(body.getOrDefault("shopTitle", "").toString());
        sp.setShopDescription(body.getOrDefault("shopDescription", "").toString());
        String title = sp.getShopTitle() != null && !sp.getShopTitle().isBlank()
                ? sp.getShopTitle() : sp.getProduct().getName();
        sp.setSlug(ShopProduct.slugify(title));
        if (body.containsKey("priceOverride")) {
            sp.setPriceOverride(new BigDecimal(body.get("priceOverride").toString()));
        }
        if (body.containsKey("stock")) {
            sp.setStock(Integer.parseInt(body.get("stock").toString()));
            sp.setStockEnabled(true);
        }

        shopProductRepository.save(sp);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("id", sp.getId(), "slug", sp.getSlug()));
    }

    // ── Commandes ─────────────────────────────────────────────────────────────

    @GetMapping("/orders")
    public ResponseEntity<?> listOrders(@RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        ShopVendor vendor = resolveVendor(apiKey);
        if (vendor == null) return unauthorized();

        // Commandes contenant au moins un produit du vendeur
        List<Map<String, Object>> result = shopOrderRepository.findAllOrderByCreatedAtDesc()
                .stream()
                .filter(o -> o.getLines().stream()
                        .anyMatch(l -> l.getShopProduct() != null
                                && vendor.getId().equals(
                                    l.getShopProduct().getVendor() != null
                                            ? l.getShopProduct().getVendor().getId() : null)))
                .map(o -> Map.<String, Object>of(
                        "reference",  o.getReference(),
                        "status",     o.getStatus().name(),
                        "statusLabel",o.getStatus().label,
                        "totalTTC",   o.getTotalTTC(),
                        "createdAt",  o.getCreatedAt().toString()
                ))
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }
}
