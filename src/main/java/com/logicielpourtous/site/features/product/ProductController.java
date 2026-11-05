package com.logicielpourtous.site.features.product;

import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.shop.PricingRule;
import com.logicielpourtous.site.features.shop.PricingRuleRepository;
import com.logicielpourtous.site.features.shop.ShopPriceHistory;
import com.logicielpourtous.site.features.shop.ShopPriceHistoryRepository;
import com.logicielpourtous.site.features.shop.ShopProduct;
import com.logicielpourtous.site.features.shop.ShopProductRepository;
import com.logicielpourtous.site.features.supplier.SupplierProduct;
import com.logicielpourtous.site.features.supplier.SupplierProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/intra/products")
public class ProductController {

    private static final Logger log = LoggerFactory.getLogger(ProductController.class);

    private final ProductRepository          productRepository;
    private final ProductCategoryRepository  productCategoryRepository;
    private final CustomerRepository         customerRepository;
    private final SupplierProductRepository  supplierProductRepository;
    private final ShopProductRepository      shopProductRepository;
    private final PricingRuleRepository      pricingRuleRepository;
    private final ShopPriceHistoryRepository priceHistoryRepository;

    @Value("${app.upload-dir}")
    private String uploadDir;

    public ProductController(ProductRepository productRepository,
                             ProductCategoryRepository productCategoryRepository,
                             CustomerRepository customerRepository,
                             SupplierProductRepository supplierProductRepository,
                             ShopProductRepository shopProductRepository,
                             PricingRuleRepository pricingRuleRepository,
                             ShopPriceHistoryRepository priceHistoryRepository) {
        this.productRepository         = productRepository;
        this.productCategoryRepository = productCategoryRepository;
        this.customerRepository        = customerRepository;
        this.supplierProductRepository = supplierProductRepository;
        this.shopProductRepository     = shopProductRepository;
        this.pricingRuleRepository     = pricingRuleRepository;
        this.priceHistoryRepository    = priceHistoryRepository;
    }

    // ── Liste ──────────────────────────────────────────────────────────────────

    @GetMapping
    public String list(@RequestParam(defaultValue = "")  String q,
                       @RequestParam(defaultValue = "")  String type,
                       @RequestParam(required = false)   Long   categoryId,
                       @RequestParam(defaultValue = "")  String boutique,
                       @RequestParam(defaultValue = "")  String image,
                       @RequestParam(defaultValue = "0")  int   page,
                       @RequestParam(defaultValue = "50") int   size,
                       Model model) {

        Product.ProductType typeEnum = null;
        if (!type.isBlank()) {
            try { typeEnum = Product.ProductType.valueOf(type); } catch (IllegalArgumentException ignored) {}
        }

        Specification<Product> spec = buildSpec(q, typeEnum, categoryId, boutique, image);
        Page<Product> productPage = productRepository.findAll(spec,
                PageRequest.of(page, size, Sort.by("name")));

        // Statuts boutique + PA pour la page courante
        Set<Long> pageIds = productPage.getContent().stream()
                .map(Product::getId).collect(Collectors.toSet());
        Set<Long> publishedIds = pageIds.isEmpty()
                ? Set.of() : shopProductRepository.findPublishedProductIdsIn(pageIds);
        Set<Long> inShopIds = pageIds.isEmpty()
                ? Set.of() : shopProductRepository.findProductIdsIn(pageIds);
        Map<Long, BigDecimal> paMap = pageIds.isEmpty() ? Map.of()
                : supplierProductRepository.findMinPaByProductIds(pageIds).stream()
                        .collect(Collectors.toMap(r -> (Long) r[0], r -> (BigDecimal) r[1]));

        model.addAttribute("productPage",   productPage);
        model.addAttribute("publishedIds",  publishedIds);
        model.addAttribute("inShopIds",     inShopIds);
        model.addAttribute("paMap",         paMap);
        model.addAttribute("q",             q);
        model.addAttribute("selectedType",  type);
        model.addAttribute("categoryId",    categoryId);
        model.addAttribute("boutique",      boutique);
        model.addAttribute("image",         image);
        model.addAttribute("size",          size);
        model.addAttribute("pageWindow",    buildPageWindow(productPage.getNumber(), productPage.getTotalPages()));
        model.addAttribute("rootCategories", productCategoryRepository.findRootCategoriesWithChildren());
        model.addAttribute("productTypes",   Product.ProductType.values());
        model.addAttribute("pricingRules",   pricingRuleRepository.findAllByOrderByNameAsc());
        model.addAttribute("totalActive",    productRepository.countByActiveTrue());
        model.addAttribute("countService",   productRepository.countByType(Product.ProductType.SERVICE));
        model.addAttribute("countPhysical",  productRepository.countByType(Product.ProductType.PHYSICAL));
        model.addAttribute("countLicense",   productRepository.countByType(Product.ProductType.LICENSE));
        model.addAttribute("countSub",       productRepository.countByType(Product.ProductType.SUBSCRIPTION));
        model.addAttribute("total",             productRepository.count());
        model.addAttribute("withoutImageCount", productRepository.countByPhotoPathIsNull());
        return "intra/products/list";
    }

    // ── Actions groupées ───────────────────────────────────────────────────────

    @PostMapping("/bulk")
    @Transactional
    public String bulk(@RequestParam(required = false) List<Long> ids,
                       @RequestParam String action,
                       @RequestParam(required = false) Long ruleId,
                       Principal principal,
                       RedirectAttributes ra) {

        if (ids == null || ids.isEmpty()) {
            ra.addFlashAttribute("errorMessage", "Aucun produit sélectionné.");
            return "redirect:/intra/products";
        }
        String user = principal != null ? principal.getName() : "système";
        int done = 0;

        switch (action) {
            case "publish" -> {
                int fetched = 0;
                for (Long id : ids) {
                    Product p = productRepository.findById(id).orElse(null);
                    if (p == null) continue;
                    ShopProduct sp = shopProductRepository.findByProductId(id).orElseGet(() -> {
                        ShopProduct s = new ShopProduct();
                        s.setProduct(p);
                        s.setSlug(uniqueSlug(p.getName()));
                        return s;
                    });
                    sp.setPublished(true);
                    // 1. Sync rapide product.photoPath → featuredImagePath (pas d'appel HTTP)
                    if (sp.getFeaturedImagePath() == null && p.getPhotoPath() != null)
                        sp.setFeaturedImagePath(p.getPhotoPath().replaceFirst("^/uploads/", ""));
                    shopProductRepository.save(sp);
                    // 2. Si toujours sans image, tentative de récupération automatique
                    if (sp.getFeaturedImagePath() == null && doFetchImage(p, null) != null)
                        fetched++;
                    done++;
                }
                String msg = done + " produit(s) publiés en boutique";
                if (fetched > 0) msg += " — " + fetched + " image(s) récupérée(s) automatiquement";
                ra.addFlashAttribute("successMessage", msg + ".");
            }
            case "unpublish" -> {
                for (Long id : ids) {
                    shopProductRepository.findByProductId(id).ifPresent(sp -> {
                        sp.setPublished(false);
                        shopProductRepository.save(sp);
                    });
                    done++;
                }
                ra.addFlashAttribute("successMessage", done + " produit(s) retirés de la boutique.");
            }
            case "apply-rule" -> {
                if (ruleId == null) {
                    ra.addFlashAttribute("errorMessage", "Sélectionnez une règle de prix.");
                    return "redirect:/intra/products";
                }
                PricingRule rule = pricingRuleRepository.findById(ruleId).orElse(null);
                if (rule == null) {
                    ra.addFlashAttribute("errorMessage", "Règle introuvable.");
                    return "redirect:/intra/products";
                }
                // PA fournisseur minimum en une seule requête
                Map<Long, BigDecimal> paByProduct = supplierProductRepository
                        .findMinPaByProductIds(ids).stream()
                        .collect(Collectors.toMap(r -> (Long) r[0], r -> (BigDecimal) r[1]));
                int skipped = 0;
                for (Long id : ids) {
                    Product p = productRepository.findById(id).orElse(null);
                    if (p == null) continue;

                    // PA fournisseur en priorité, sinon prix HT interne du produit
                    BigDecimal pa = paByProduct.containsKey(id)
                            ? paByProduct.get(id)
                            : p.getPriceHT();
                    if (pa == null || pa.compareTo(BigDecimal.ZERO) == 0) { skipped++; continue; }

                    BigDecimal newPrice = rule.applyTo(pa);
                    if (newPrice == null) { skipped++; continue; }

                    ShopProduct sp = shopProductRepository.findByProductId(id).orElseGet(() -> {
                        ShopProduct s = new ShopProduct();
                        s.setProduct(p);
                        s.setSlug(uniqueSlug(p.getName()));
                        return s;
                    });

                    // Historique si le prix change
                    BigDecimal oldPrice = sp.getPriceOverride() != null ? sp.getPriceOverride() : p.getPriceHT();
                    if (oldPrice == null || oldPrice.compareTo(newPrice) != 0) {
                        priceHistoryRepository.save(ShopPriceHistory.of(
                                id, oldPrice, newPrice, "Règle: " + rule.getName(), user));
                    }

                    // Synchroniser shopProduct.priceOverride ET product.priceHT ensemble
                    sp.setPriceOverride(newPrice);
                    sp.setPublished(true);
                    p.setPriceHT(newPrice);
                    productRepository.save(p);
                    shopProductRepository.save(sp);
                    done++;
                }
                String msg = done + " produit(s) mis à jour avec « " + rule.getName() + " »";
                if (skipped > 0) msg += " — " + skipped + " ignoré(s) (prix de base nul)";
                ra.addFlashAttribute("successMessage", msg + ".");
            }
            case "fetch-images" -> {
                int fetched = 0, skipped = 0;
                for (Long id : ids) {
                    Product p = productRepository.findById(id).orElse(null);
                    if (p == null) continue;
                    if (doFetchImage(p, null) != null) fetched++; else skipped++;
                }
                String msg = fetched + " image(s) récupérée(s)";
                if (skipped > 0) msg += " — " + skipped + " sans résultat";
                ra.addFlashAttribute("successMessage", msg + ".");
            }
            default -> ra.addFlashAttribute("errorMessage", "Action inconnue.");
        }
        return "redirect:/intra/products";
    }

    private String uniqueSlug(String base) {
        String slug = ShopProduct.slugify(base);
        String candidate = slug;
        int suffix = 2;
        while (shopProductRepository.findBySlug(candidate).isPresent()) {
            candidate = slug + "-" + suffix++;
        }
        return candidate;
    }

    private Specification<Product> buildSpec(String q, Product.ProductType type, Long categoryId, String boutique, String image) {
        return (root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            if (q != null && !q.isBlank())
                predicates.add(cb.like(cb.lower(root.get("name")), "%" + q.toLowerCase() + "%"));
            if (type != null)
                predicates.add(cb.equal(root.get("type"), type));
            if (categoryId != null)
                predicates.add(cb.equal(root.get("category").get("id"), categoryId));
            if ("sans".equals(image))
                predicates.add(cb.isNull(root.get("photoPath")));
            else if ("avec".equals(image))
                predicates.add(cb.isNotNull(root.get("photoPath")));
            if ("publie".equals(boutique)) {
                var sub = query.subquery(Long.class);
                var sp  = sub.from(ShopProduct.class);
                sub.select(sp.get("product").get("id"))
                   .where(cb.equal(sp.get("published"), true));
                predicates.add(root.get("id").in(sub));
            } else if ("reference".equals(boutique)) {
                var sub = query.subquery(Long.class);
                var sp  = sub.from(ShopProduct.class);
                sub.select(sp.get("product").get("id"));
                predicates.add(root.get("id").in(sub));
            } else if ("absent".equals(boutique)) {
                var sub = query.subquery(Long.class);
                var sp  = sub.from(ShopProduct.class);
                sub.select(sp.get("product").get("id"));
                predicates.add(cb.not(root.get("id").in(sub)));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    private List<Integer> buildPageWindow(int current, int total) {
        List<Integer> pages = new ArrayList<>();
        if (total <= 7) {
            for (int i = 0; i < total; i++) pages.add(i);
            return pages;
        }
        pages.add(0);
        int from = Math.max(1, current - 2);
        int to   = Math.min(total - 2, current + 2);
        if (from > 1) pages.add(-1);
        for (int i = from; i <= to; i++) pages.add(i);
        if (to < total - 2) pages.add(-1);
        pages.add(total - 1);
        return pages;
    }

    // ── Nouveau produit ────────────────────────────────────────────────────────

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("rootCategories", productCategoryRepository.findRootCategoriesWithChildren());
        model.addAttribute("productTypes",   Product.ProductType.values());
        model.addAttribute("pricingModes",   Product.PricingMode.values());
        model.addAttribute("suppliers",      customerRepository.findSuppliers());
        return "intra/products/form";
    }

    @PostMapping
    public String create(@RequestParam String name,
                         @RequestParam(required = false) String description,
                         @RequestParam(required = false) String sku,
                         @RequestParam(required = false) String reference,
                         @RequestParam(required = false) BigDecimal priceHT,
                         @RequestParam(required = false) BigDecimal taxRate,
                         @RequestParam(required = false) Product.ProductType type,
                         @RequestParam(required = false) Product.PricingMode pricingMode,
                         @RequestParam(required = false) Long categoryId,
                         @RequestParam(required = false) MultipartFile photo,
                         RedirectAttributes ra) {

        Product p = new Product();
        p.setName(name);
        p.setDescription(description);
        p.setSku(sku);
        p.setReference(reference);
        p.setPriceHT(priceHT != null ? priceHT : BigDecimal.ZERO);
        p.setTaxRate(taxRate != null ? taxRate : new BigDecimal("20.00"));
        p.setType(type != null ? type : Product.ProductType.SERVICE);
        p.setPricingMode(pricingMode != null ? pricingMode : Product.PricingMode.PER_UNIT);
        if (categoryId != null) productCategoryRepository.findById(categoryId).ifPresent(p::setCategory);
        p = productRepository.save(p);

        if (photo != null && !photo.isEmpty()) {
            try {
                String ext = getExtension(photo.getOriginalFilename());
                Path dest = Paths.get(uploadDir, "products", p.getId() + "." + ext);
                Files.createDirectories(dest.getParent());
                photo.transferTo(dest.toFile());
                p.setPhotoPath("/uploads/products/" + p.getId() + "." + ext);
                productRepository.save(p);
            } catch (IOException e) {
                log.error("Erreur upload photo produit id={}: {}", p.getId(), e.getMessage());
            }
        }

        ra.addFlashAttribute("successMessage", "Produit « " + name + " » créé.");
        return "redirect:/intra/products/" + p.getId();
    }

    // ── Détail ─────────────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public String detail(@PathVariable Long id, Model model) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Produit introuvable : " + id));

        product.getSupplierProducts().size();
        if (product.getCategory() != null) product.getCategory().getName();

        List<SupplierProduct> supplierProducts = supplierProductRepository.findByProductIdOrderBySupplierId(id);
        for (SupplierProduct sp : supplierProducts) {
            sp.getSupplier().getCompanyName();
            sp.getProduct().getName();
        }

        BigDecimal priceTTC = BigDecimal.ZERO;
        if (product.getPriceHT() != null && product.getTaxRate() != null) {
            BigDecimal factor = BigDecimal.ONE.add(
                    product.getTaxRate().divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP));
            priceTTC = product.getPriceHT().multiply(factor).setScale(2, RoundingMode.HALF_UP);
        }

        ShopProduct shopProduct = shopProductRepository.findByProductId(id).orElse(null);

        // Codes disponibles pour la recherche d'image (priorité : UPC > VPN > réf Ingram > SKU)
        String supplierUpcCode = supplierProducts.stream()
                .map(SupplierProduct::getUpcCode)
                .filter(c -> c != null && !c.isBlank()).findFirst().orElse(null);
        String supplierVpn = supplierProducts.stream()
                .map(SupplierProduct::getVendorPartNumber)
                .filter(c -> c != null && !c.isBlank()).findFirst().orElse(null);
        String supplierRef = supplierProducts.stream()
                .map(SupplierProduct::getSupplierReference)
                .filter(c -> c != null && !c.isBlank()).findFirst().orElse(null);
        String bestImageCode = supplierUpcCode != null ? supplierUpcCode
                : supplierVpn != null ? supplierVpn
                : supplierRef != null ? supplierRef
                : product.getSku();
        String bestImageCodeType = supplierUpcCode != null ? "UPC/EAN"
                : supplierVpn != null ? "Réf. fabricant"
                : supplierRef != null ? "Réf. fournisseur"
                : "SKU";

        model.addAttribute("product",          product);
        model.addAttribute("priceTTC",         priceTTC);
        model.addAttribute("rootCategories",   productCategoryRepository.findRootCategoriesWithChildren());
        model.addAttribute("suppliers",        customerRepository.findSuppliers());
        model.addAttribute("pricingModes",     Product.PricingMode.values());
        model.addAttribute("productTypes",     Product.ProductType.values());
        model.addAttribute("supplierProducts", supplierProducts);
        model.addAttribute("shopProduct",      shopProduct);
        model.addAttribute("pricingRules",     pricingRuleRepository.findAllByOrderByNameAsc());
        model.addAttribute("defaultRule",      pricingRuleRepository.findFirstByIsDefaultTrue().orElse(null));
        model.addAttribute("priceHistory",     priceHistoryRepository.findByProductIdOrderByChangedAtDesc(id));
        model.addAttribute("bestImageCode",    bestImageCode);
        model.addAttribute("bestImageCodeType",bestImageCodeType);
        return "intra/products/detail";
    }

    // ── Récupération automatique d'image via UPC/EAN ──────────────────────────

    @PostMapping("/{id}/fetch-image")
    @Transactional
    public String fetchImage(@PathVariable Long id,
                             @RequestParam(required = false) String ean,
                             RedirectAttributes ra) {
        Product p = productRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Produit introuvable : " + id));
        String result = doFetchImage(p, ean);
        if (result != null) {
            ra.addFlashAttribute("successMessage", "Image récupérée et synchronisée avec la boutique.");
        } else {
            ra.addFlashAttribute("errorMessage",
                    "Aucune image trouvée (code : " + (ean != null && !ean.isBlank() ? ean : "auto") + "). "
                  + "Essayez un code EAN différent.");
        }
        return "redirect:/intra/products/" + id;
    }

    /**
     * Télécharge une image depuis UPCitemdb et met à jour :
     *  - product.photoPath           (/uploads/products/{id}.ext)
     *  - shopProduct.featuredImagePath (products/{id}.ext — chemin relatif pour la boutique)
     *
     * @param p            le produit cible
     * @param codeOverride code EAN/UPC saisi manuellement (null = auto-détection)
     * @return chemin relatif "products/{id}.ext" si succès, null sinon
     */
    private String doFetchImage(Product p, String codeOverride) {
        // Choix du code
        String code;
        if (codeOverride != null && !codeOverride.isBlank()) {
            code = codeOverride.trim();
        } else {
            List<SupplierProduct> sps = supplierProductRepository.findByProductIdOrderBySupplierId(p.getId());
            code = sps.stream().map(SupplierProduct::getUpcCode)
                    .filter(c -> c != null && !c.isBlank()).findFirst()
                    .orElseGet(() -> sps.stream().map(SupplierProduct::getVendorPartNumber)
                            .filter(c -> c != null && !c.isBlank()).findFirst()
                            .orElseGet(() -> sps.stream().map(SupplierProduct::getSupplierReference)
                                    .filter(c -> c != null && !c.isBlank()).findFirst()
                                    .orElse(p.getSku())));
        }
        if (code == null || code.isBlank()) return null;

        try {
            String apiUrl = "https://api.upcitemdb.com/prod/trial/lookup?upc="
                          + URLEncoder.encode(code, StandardCharsets.UTF_8);
            HttpResponse<String> resp = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder().uri(URI.create(apiUrl))
                            .header("Accept", "application/json").GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            JsonNode items = new ObjectMapper().readTree(resp.body()).path("items");
            if (items.isEmpty()) return null;

            // Collect all image URLs from all items
            List<String> imageUrls = new ArrayList<>();
            for (JsonNode item : items) {
                for (JsonNode img : item.path("images")) {
                    String u = img.asText();
                    if (!u.isBlank()) imageUrls.add(u);
                }
            }
            if (imageUrls.isEmpty()) return null;

            // Browser-like headers to bypass hotlink protection
            HttpClient httpClient = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            // Nom SEO : {slug-nom}[-{slug-ref}]-{id}.{ext}
            String nameSlug = ShopProduct.slugify(p.getName());
            if (nameSlug.length() > 60) nameSlug = nameSlug.substring(0, 60).replaceAll("-+$", "");
            String refSlug = "";
            if (p.getReference() != null && !p.getReference().isBlank())
                refSlug = "-" + ShopProduct.slugify(p.getReference());
            else if (p.getSku() != null && !p.getSku().isBlank())
                refSlug = "-" + ShopProduct.slugify(p.getSku());
            final String seoBase = nameSlug + refSlug + "-" + p.getId();

            // Try each URL until one downloads successfully
            byte[] imageBytes = null;
            String chosenUrl = null;
            String ext = "jpg";
            for (String imageUrl : imageUrls) {
                try {
                    // Determine extension from URL
                    String urlPathPart = imageUrl.split("\\?")[0];
                    int dot = urlPathPart.lastIndexOf('.');
                    String tryExt = "jpg";
                    if (dot > 0 && dot < urlPathPart.length() - 1) {
                        String e = urlPathPart.substring(dot + 1).toLowerCase();
                        if (List.of("jpg", "jpeg", "png", "webp").contains(e))
                            tryExt = e.equals("jpeg") ? "jpg" : e;
                    }
                    HttpRequest imgReq = HttpRequest.newBuilder()
                            .uri(URI.create(imageUrl))
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0 Safari/537.36")
                            .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                            .header("Referer", "https://www.google.com/")
                            .GET().build();
                    HttpResponse<byte[]> imgResp = httpClient.send(imgReq, HttpResponse.BodyHandlers.ofByteArray());
                    if (imgResp.statusCode() == 200 && imgResp.body().length > 1000) {
                        imageBytes = imgResp.body();
                        chosenUrl = imageUrl;
                        ext = tryExt;
                        break;
                    }
                    log.debug("doFetchImage skip url={} status={}", imageUrl, imgResp.statusCode());
                } catch (Exception ex) {
                    log.debug("doFetchImage skip url={}: {}", imageUrl, ex.getMessage());
                }
            }
            if (imageBytes == null) return null;

            String filename = seoBase + "." + ext;

            // Téléchargement
            Path dest = Paths.get(uploadDir, "products", filename);
            Files.createDirectories(dest.getParent());
            Files.write(dest, imageBytes);

            // Chemins : absolu pour product, relatif pour boutique (Thymeleaf ajoute /uploads/)
            String relativePath = "products/" + filename;
            p.setPhotoPath("/uploads/" + relativePath);
            productRepository.save(p);

            // ── Synchronisation boutique ───────────────────────────────────────
            shopProductRepository.findByProductId(p.getId()).ifPresent(sp -> {
                sp.setFeaturedImagePath(relativePath);
                shopProductRepository.save(sp);
            });

            log.info("Image récupérée produit id={} code={} url={} → {}", p.getId(), code, chosenUrl, relativePath);
            return relativePath;

        } catch (Exception e) {
            log.warn("doFetchImage produit id={} code={}: {}", p.getId(), code, e.getMessage());
            return null;
        }
    }


    // ── Mise à jour ────────────────────────────────────────────────────────────

    @PostMapping("/{id}/update")
    public String update(@PathVariable Long id,
                         @RequestParam String name,
                         @RequestParam(required = false) String description,
                         @RequestParam(required = false) String sku,
                         @RequestParam(required = false) String reference,
                         @RequestParam(required = false) BigDecimal priceHT,
                         @RequestParam(required = false) BigDecimal taxRate,
                         @RequestParam(required = false) Product.ProductType type,
                         @RequestParam(required = false) Product.PricingMode pricingMode,
                         @RequestParam(required = false) Long categoryId,
                         @RequestParam(required = false) MultipartFile photo,
                         @RequestParam(required = false, defaultValue = "false") boolean active,
                         RedirectAttributes ra) {

        Product p = productRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Produit introuvable : " + id));
        p.setName(name);
        p.setDescription(description);
        p.setSku(sku);
        p.setReference(reference);
        if (priceHT != null)    p.setPriceHT(priceHT);
        if (taxRate != null)    p.setTaxRate(taxRate);
        if (type != null)       p.setType(type);
        if (pricingMode != null) p.setPricingMode(pricingMode);
        p.setActive(active);
        p.setCategory(categoryId != null
                ? productCategoryRepository.findById(categoryId).orElse(null) : null);
        p = productRepository.save(p);

        if (photo != null && !photo.isEmpty()) {
            try {
                String ext = getExtension(photo.getOriginalFilename());
                Path dest = Paths.get(uploadDir, "products", p.getId() + "." + ext);
                Files.createDirectories(dest.getParent());
                photo.transferTo(dest.toFile());
                p.setPhotoPath("/uploads/products/" + p.getId() + "." + ext);
                productRepository.save(p);
            } catch (IOException e) {
                log.error("Erreur upload photo produit id={}: {}", p.getId(), e.getMessage());
            }
        }

        ra.addFlashAttribute("successMessage", "Produit « " + name + " » mis à jour.");
        return "redirect:/intra/products/" + id;
    }

    // ── Toggle actif ───────────────────────────────────────────────────────────

    @PostMapping("/{id}/toggle")
    public String toggle(@PathVariable Long id, RedirectAttributes ra) {
        productRepository.findById(id).ifPresent(p -> {
            p.setActive(!p.isActive());
            productRepository.save(p);
        });
        return "redirect:/intra/products";
    }

    // ── Supprimer ──────────────────────────────────────────────────────────────

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        productRepository.findById(id).ifPresent(p -> {
            productRepository.delete(p);
            ra.addFlashAttribute("successMessage", "Produit supprimé.");
        });
        return "redirect:/intra/products";
    }

    // ── Prix fournisseurs ──────────────────────────────────────────────────────

    @PostMapping("/{id}/supplier-prices")
    public String addOrUpdateSupplierPrice(@PathVariable Long id,
                                           @RequestParam Long supplierId,
                                           @RequestParam(required = false) String supplierReference,
                                           @RequestParam(required = false) String supplierName,
                                           @RequestParam BigDecimal purchasePrice,
                                           @RequestParam(required = false) Integer stockQuantity,
                                           RedirectAttributes ra) {

        Product product = productRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Produit introuvable : " + id));
        Customer supplier = customerRepository.findById(supplierId)
                .orElseThrow(() -> new IllegalArgumentException("Fournisseur introuvable : " + supplierId));

        SupplierProduct sp = supplierProductRepository.findByProductIdOrderBySupplierId(id).stream()
                .filter(s -> s.getSupplier().getId().equals(supplierId))
                .findFirst()
                .orElse(null);

        if (sp == null) {
            sp = new SupplierProduct();
            sp.setProduct(product);
            sp.setSupplier(supplier);
        }
        sp.setSupplierReference(supplierReference);
        sp.setSupplierName(supplierName);
        sp.setPurchasePrice(purchasePrice);
        sp.setStockQuantity(stockQuantity);
        supplierProductRepository.save(sp);

        ra.addFlashAttribute("successMessage", "Prix fournisseur mis à jour.");
        return "redirect:/intra/products/" + id;
    }

    @PostMapping("/{id}/supplier-prices/{spId}/update-pa")
    @Transactional
    public String updateSupplierPa(@PathVariable Long id,
                                   @PathVariable Long spId,
                                   @RequestParam BigDecimal purchasePrice,
                                   @RequestParam(required = false) Long ruleId,
                                   Principal principal,
                                   RedirectAttributes ra) {

        SupplierProduct sp = supplierProductRepository.findById(spId)
                .filter(s -> s.getProduct().getId().equals(id))
                .orElse(null);
        if (sp == null) {
            ra.addFlashAttribute("errorMessage", "Prix fournisseur introuvable.");
            return "redirect:/intra/products/" + id;
        }

        sp.setPurchasePrice(purchasePrice);
        supplierProductRepository.save(sp);

        // Appliquer la règle pour mettre à jour le prix de vente
        PricingRule rule = null;
        if (ruleId != null) {
            rule = pricingRuleRepository.findById(ruleId).orElse(null);
        }
        if (rule == null) {
            rule = pricingRuleRepository.findFirstByIsDefaultTrue().orElse(null);
        }

        if (rule != null) {
            Product product = sp.getProduct();
            BigDecimal newPrice = rule.applyTo(purchasePrice);
            String user = principal != null ? principal.getName() : "système";

            ShopProduct shopProd = shopProductRepository.findByProductId(id).orElseGet(() -> {
                ShopProduct s = new ShopProduct();
                s.setProduct(product);
                s.setSlug(uniqueSlug(product.getName()));
                return s;
            });

            BigDecimal oldPrice = shopProd.getPriceOverride() != null
                    ? shopProd.getPriceOverride() : product.getPriceHT();
            if (oldPrice == null || oldPrice.compareTo(newPrice) != 0) {
                priceHistoryRepository.save(ShopPriceHistory.of(
                        id, oldPrice, newPrice, "Règle: " + rule.getName(), user));
            }

            shopProd.setPriceOverride(newPrice);
            product.setPriceHT(newPrice);
            productRepository.save(product);
            shopProductRepository.save(shopProd);

            ra.addFlashAttribute("successMessage",
                    "PA mis à jour — prix de vente recalculé à " +
                    String.format("%.2f", newPrice).replace('.', ',') + " € (règle « " + rule.getName() + " »).");
        } else {
            ra.addFlashAttribute("successMessage", "PA mis à jour.");
        }
        return "redirect:/intra/products/" + id;
    }

    @PostMapping("/{id}/supplier-prices/{spId}/delete")
    public String deleteSupplierPrice(@PathVariable Long id,
                                      @PathVariable Long spId,
                                      RedirectAttributes ra) {
        supplierProductRepository.findById(spId).ifPresent(sp -> {
            if (sp.getProduct().getId().equals(id)) {
                supplierProductRepository.delete(sp);
            }
        });
        ra.addFlashAttribute("successMessage", "Prix fournisseur supprimé.");
        return "redirect:/intra/products/" + id;
    }

    // ── Boutique ───────────────────────────────────────────────────────────────

    @PostMapping("/{id}/shop/publish")
    public String shopTogglePublish(@PathVariable Long id, RedirectAttributes ra) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Produit introuvable : " + id));
        ShopProduct sp = shopProductRepository.findByProductId(id).orElseGet(() -> {
            ShopProduct s = new ShopProduct();
            s.setProduct(product);
            s.setSlug(ShopProduct.slugify(product.getName()));
            return s;
        });
        sp.setPublished(!sp.isPublished());
        shopProductRepository.save(sp);
        ra.addFlashAttribute("successMessage",
                sp.isPublished() ? "Produit publié en boutique." : "Produit retiré de la boutique.");
        return "redirect:/intra/products/" + id;
    }

    @PostMapping("/{id}/shop/update")
    public String shopUpdate(@PathVariable Long id,
                             @RequestParam(required = false) String slug,
                             @RequestParam(required = false) String shopTitle,
                             @RequestParam(required = false) String shopDescription,
                             @RequestParam(required = false) String seoTitle,
                             @RequestParam(required = false) String seoDescription,
                             @RequestParam(required = false) BigDecimal priceOverride,
                             @RequestParam(required = false) Long ruleId,
                             @RequestParam(defaultValue = "false") boolean stockEnabled,
                             @RequestParam(defaultValue = "0") int stock,
                             Principal principal,
                             RedirectAttributes ra) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Produit introuvable : " + id));
        ShopProduct sp = shopProductRepository.findByProductId(id).orElseGet(() -> {
            ShopProduct s = new ShopProduct();
            s.setProduct(product);
            return s;
        });
        String finalSlug = (slug != null && !slug.isBlank()) ? slug
                : (sp.getSlug() != null ? sp.getSlug() : ShopProduct.slugify(product.getName()));
        sp.setSlug(finalSlug);
        sp.setShopTitle(shopTitle != null && !shopTitle.isBlank() ? shopTitle : null);
        sp.setShopDescription(shopDescription != null && !shopDescription.isBlank() ? shopDescription : null);
        sp.setSeoTitle(seoTitle != null && !seoTitle.isBlank() ? seoTitle : null);
        sp.setSeoDescription(seoDescription != null && !seoDescription.isBlank() ? seoDescription : null);
        sp.setStockEnabled(stockEnabled);
        sp.setStock(stock);

        // Appliquer règle OU prix manuel
        BigDecimal computedPrice = priceOverride;
        String source = "Manuel";
        if (ruleId != null) {
            PricingRule rule = pricingRuleRepository.findById(ruleId).orElse(null);
            if (rule != null && product.getPriceHT() != null) {
                computedPrice = rule.applyTo(product.getPriceHT());
                source = "Règle: " + rule.getName();
            }
        }

        // Historique si le prix change
        if (computedPrice != null) {
            BigDecimal oldPrice = sp.getPriceOverride() != null ? sp.getPriceOverride() : product.getPriceHT();
            if (oldPrice == null || oldPrice.compareTo(computedPrice) != 0) {
                priceHistoryRepository.save(ShopPriceHistory.of(
                        id, oldPrice, computedPrice, source,
                        principal != null ? principal.getName() : "système"));
            }
            sp.setPriceOverride(computedPrice);
            product.setPriceHT(computedPrice);
            productRepository.save(product);
        } else {
            sp.setPriceOverride(null);
        }

        shopProductRepository.save(sp);
        ra.addFlashAttribute("successMessage", "Paramètres boutique mis à jour.");
        return "redirect:/intra/products/" + id;
    }

    // ── Catégorie ──────────────────────────────────────────────────────────────

    @PostMapping("/{id}/category")
    public String updateCategory(@PathVariable Long id,
                                 @RequestParam(required = false) Long categoryId,
                                 RedirectAttributes ra) {
        productRepository.findById(id).ifPresent(p -> {
            p.setCategory(categoryId != null
                    ? productCategoryRepository.findById(categoryId).orElse(null) : null);
            productRepository.save(p);
        });
        ra.addFlashAttribute("successMessage", "Catégorie mise à jour.");
        return "redirect:/intra/products/" + id;
    }

    // ── Recherche AJAX ─────────────────────────────────────────────────────────

    @GetMapping("/search")
    @ResponseBody
    public List<Map<String, Object>> search(@RequestParam(defaultValue = "") String q) {
        if (q.isBlank() || q.length() < 2) return List.of();
        return productRepository.findTop20ByActiveTrueAndNameContainingIgnoreCaseOrderByNameAsc(q)
                .stream()
                .map(p -> Map.<String, Object>of(
                        "id",    p.getId(),
                        "name",  p.getName(),
                        "price", p.getPriceHT() != null ? p.getPriceHT() : BigDecimal.ZERO))
                .toList();
    }

    // ── Helper ─────────────────────────────────────────────────────────────────

    private String getExtension(String filename) {
        if (filename == null) return "jpg";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1) : "jpg";
    }
}
