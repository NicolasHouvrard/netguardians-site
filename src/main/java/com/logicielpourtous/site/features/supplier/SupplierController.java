package com.logicielpourtous.site.features.supplier;

import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.product.Product;
import com.logicielpourtous.site.features.product.ProductRepository;
import com.logicielpourtous.site.features.shop.PricingRule;
import com.logicielpourtous.site.features.shop.PricingRuleRepository;
import com.logicielpourtous.site.features.shop.ShopPriceHistory;
import com.logicielpourtous.site.features.shop.ShopPriceHistoryRepository;
import com.logicielpourtous.site.features.shop.ShopProduct;
import com.logicielpourtous.site.features.shop.ShopProductRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.annotation.Secured;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Controller
@RequestMapping("/intra/suppliers")
public class SupplierController {

    private final CustomerRepository         customerRepository;
    private final SupplierProductRepository  supplierProductRepository;
    private final ProductRepository          productRepository;
    private final SupplierSyncService        supplierSyncService;
    private final IngramMicroService         ingramMicroService;
    private final IngramSyncTracker          syncTracker;
    private final ShopProductRepository      shopProductRepository;
    private final PricingRuleRepository      pricingRuleRepository;
    private final ShopPriceHistoryRepository priceHistoryRepository;

    public SupplierController(CustomerRepository customerRepository,
                              SupplierProductRepository supplierProductRepository,
                              ProductRepository productRepository,
                              SupplierSyncService supplierSyncService,
                              IngramMicroService ingramMicroService,
                              IngramSyncTracker syncTracker,
                              ShopProductRepository shopProductRepository,
                              PricingRuleRepository pricingRuleRepository,
                              ShopPriceHistoryRepository priceHistoryRepository) {
        this.customerRepository        = customerRepository;
        this.supplierProductRepository = supplierProductRepository;
        this.productRepository         = productRepository;
        this.supplierSyncService       = supplierSyncService;
        this.ingramMicroService        = ingramMicroService;
        this.syncTracker               = syncTracker;
        this.shopProductRepository     = shopProductRepository;
        this.pricingRuleRepository     = pricingRuleRepository;
        this.priceHistoryRepository    = priceHistoryRepository;
    }

    // ── Liste ─────────────────────────────────────────────────────────────────

    @GetMapping
    public String list(Model model) {
        List<Customer> suppliers = customerRepository.findSuppliers();
        long countWithApi = suppliers.stream()
                .filter(s -> s.getSupplierApiType() != null && s.getSupplierApiType() != ApiType.NONE)
                .count();
        Map<Long, Long> productCounts = new HashMap<>();
        for (Customer s : suppliers) {
            productCounts.put(s.getId(), supplierProductRepository.countBySupplierId(s.getId()));
        }
        model.addAttribute("suppliers",     suppliers);
        model.addAttribute("productCounts", productCounts);
        model.addAttribute("total",         suppliers.size());
        model.addAttribute("countWithApi",  countWithApi);
        return "intra/suppliers/list";
    }

    // ── Nouveau fournisseur ───────────────────────────────────────────────────

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("apiTypes", ApiType.values());
        return "intra/suppliers/form";
    }

    @PostMapping
    public String create(@RequestParam String name,
                         @RequestParam(required = false) String email,
                         @RequestParam(required = false) String phone,
                         @RequestParam(required = false) String website,
                         @RequestParam(required = false) String address,
                         @RequestParam(required = false) ApiType apiType,
                         @RequestParam(required = false) String apiKey,
                         @RequestParam(required = false) String apiUrl,
                         @RequestParam(required = false) String apiClientId,
                         @RequestParam(required = false) String customerNumber,
                         @RequestParam(required = false) String countryCode,
                         RedirectAttributes ra) {
        Customer s = new Customer();
        s.setCompanyName(name);
        s.setEmail(email);
        s.setPhone(phone);
        s.setWebsite(website);
        s.setAddress(address);
        s.setSupplier(true);
        s.setCustomer(false);
        s.setSupplierApiType(apiType != null ? apiType : ApiType.NONE);
        s.setSupplierApiKey(trimOrNull(apiKey));
        s.setSupplierApiUrl(trimOrNull(apiUrl));
        s.setSupplierApiClientId(trimOrNull(apiClientId));
        s.setSupplierCustomerNumber(trimOrNull(customerNumber));
        s.setSupplierCountryCode(countryCode != null && !countryCode.isBlank() ? countryCode.trim() : "FR");
        s = customerRepository.save(s);
        ra.addFlashAttribute("successMessage", "Fournisseur « " + name + " » créé.");
        return "redirect:/intra/suppliers/" + s.getId();
    }

    // ── Détail ────────────────────────────────────────────────────────────────

    private static final Set<Integer> ALLOWED_SIZES = Set.of(10, 20, 50, 100);

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public String detail(@PathVariable Long id,
                         @RequestParam(defaultValue = "0") int page,
                         @RequestParam(defaultValue = "10") int size,
                         @RequestParam(required = false) String search,
                         Model model) {
        Customer supplier = customerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Fournisseur introuvable : " + id));

        if (!ALLOWED_SIZES.contains(size)) size = 10;
        String q = (search != null && !search.isBlank()) ? "%" + search.trim().toLowerCase() + "%" : null;
        Page<SupplierProduct> productPage = supplierProductRepository
                .findBySupplierIdFiltered(id, q, PageRequest.of(page, size));

        // Initialize lazy product associations within the transaction
        for (SupplierProduct sp : productPage.getContent()) {
            sp.getProduct().getName();
        }

        List<Product> allProducts = productRepository.findByActiveTrueOrderByNameAsc();

        model.addAttribute("supplier",         supplier);
        model.addAttribute("productPage",      productPage);
        model.addAttribute("supplierProducts", productPage.getContent());
        model.addAttribute("allProducts",      allProducts);
        model.addAttribute("apiTypes",         ApiType.values());
        model.addAttribute("syncState",        syncTracker.get(id));
        model.addAttribute("supplierId",       id);
        model.addAttribute("search",           search != null ? search.trim() : "");
        model.addAttribute("currentSize",      size);
        model.addAttribute("pricingRules",     pricingRuleRepository.findAllByOrderByNameAsc());
        model.addAttribute("defaultRule",      pricingRuleRepository.findFirstByIsDefaultTrue().orElse(null));
        return "intra/suppliers/detail";
    }

    // ── Paramètres ───────────────────────────────────────────────────────────

    @GetMapping("/{id}/settings")
    public String settings(@PathVariable Long id, Model model) {
        Customer supplier = customerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Fournisseur introuvable : " + id));
        model.addAttribute("supplier", supplier);
        model.addAttribute("apiTypes", ApiType.values());
        return "intra/suppliers/settings";
    }

    // ── Mise à jour ───────────────────────────────────────────────────────────

    @PostMapping("/{id}/update")
    public String update(@PathVariable Long id,
                         @RequestParam String name,
                         @RequestParam(required = false) String email,
                         @RequestParam(required = false) String phone,
                         @RequestParam(required = false) String website,
                         @RequestParam(required = false) String address,
                         @RequestParam(required = false) ApiType apiType,
                         @RequestParam(required = false) String apiKey,
                         @RequestParam(required = false) String apiUrl,
                         @RequestParam(required = false) String apiClientId,
                         @RequestParam(required = false) String customerNumber,
                         @RequestParam(required = false) String countryCode,
                         RedirectAttributes ra) {
        Customer s = customerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Fournisseur introuvable : " + id));
        s.setCompanyName(name);
        s.setEmail(email);
        s.setPhone(phone);
        s.setWebsite(website);
        s.setAddress(address);
        s.setSupplierApiType(apiType != null ? apiType : ApiType.NONE);
        s.setSupplierApiKey(trimOrNull(apiKey));
        s.setSupplierApiUrl(trimOrNull(apiUrl));
        s.setSupplierApiClientId(trimOrNull(apiClientId));
        s.setSupplierCustomerNumber(trimOrNull(customerNumber));
        s.setSupplierCountryCode(countryCode != null && !countryCode.isBlank() ? countryCode.trim() : "FR");
        customerRepository.save(s);
        ra.addFlashAttribute("successMessage", "Fournisseur mis à jour.");
        return "redirect:/intra/suppliers/" + id + "/settings";
    }

    // ── Toggle actif ──────────────────────────────────────────────────────────

    @PostMapping("/{id}/toggle")
    public String toggle(@PathVariable Long id, RedirectAttributes ra) {
        customerRepository.findById(id).ifPresent(s -> {
            s.setActive(!s.isActive());
            customerRepository.save(s);
        });
        return "redirect:/intra/suppliers";
    }

    // ── Synchronisation API ───────────────────────────────────────────────────

    @PostMapping("/{id}/sync")
    public String sync(@PathVariable Long id, RedirectAttributes ra) {
        Customer supplier = customerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Fournisseur introuvable : " + id));
        SupplierSyncService.SyncResult result = supplierSyncService.sync(supplier);
        ra.addFlashAttribute("syncCreated", result.created());
        ra.addFlashAttribute("syncUpdated", result.updated());
        ra.addFlashAttribute("syncErrors",  result.errors());
        ra.addFlashAttribute("syncMessage", result.message());
        return "redirect:/intra/suppliers/" + id;
    }

    /** Sync Ingram Micro avec filtres (marque, mot-clé, nombre max de pages). */
    @PostMapping("/{id}/sync/ingram")
    @Secured({"ROLE_ADMIN", "ROLE_COMMERCIAL"})
    public String syncIngram(@PathVariable Long id,
                             @RequestParam(defaultValue = "50") int maxPages,
                             @RequestParam(required = false) String vendor,
                             @RequestParam(required = false) String keyword,
                             RedirectAttributes ra) {
        Customer supplier = customerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Fournisseur introuvable : " + id));
        SupplierSyncService.SyncResult result = ingramMicroService.syncCatalog(
                supplier, maxPages,
                (vendor != null && !vendor.isBlank()) ? vendor : null,
                (keyword != null && !keyword.isBlank()) ? keyword : null);
        ra.addFlashAttribute("syncCreated", result.created());
        ra.addFlashAttribute("syncUpdated", result.updated());
        ra.addFlashAttribute("syncErrors",  result.errors());
        ra.addFlashAttribute("syncMessage", result.message());
        return "redirect:/intra/suppliers/" + id;
    }

    /** Démarre une synchronisation complète Ingram en arrière-plan (sans limite de pages). */
    @PostMapping("/{id}/sync/ingram/full")
    @Secured({"ROLE_ADMIN", "ROLE_COMMERCIAL"})
    public String syncIngramFull(@PathVariable Long id,
                                 @RequestParam(required = false) String vendors,
                                 @RequestParam(required = false) String category,
                                 @RequestParam(required = false) String keyword,
                                 RedirectAttributes ra) {
        customerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Fournisseur introuvable : " + id));
        IngramSyncTracker.JobState state = syncTracker.get(id);
        if (state.status == IngramSyncTracker.SyncStatus.RUNNING) {
            ra.addFlashAttribute("errorMessage", "Une synchronisation est déjà en cours.");
            return "redirect:/intra/suppliers/" + id;
        }
        // Parse comma-separated vendor list
        List<String> vendorList = (vendors != null && !vendors.isBlank())
                ? List.of(vendors.split("\\s*,\\s*")).stream()
                        .filter(v -> !v.isBlank()).toList()
                : null;
        ingramMicroService.syncCatalogFullAsync(id, null, vendorList, category, keyword);
        ra.addFlashAttribute("successMessage",
                "Synchronisation complète démarrée en arrière-plan. La progression apparaît ci-dessous.");
        return "redirect:/intra/suppliers/" + id;
    }

    /** Rafraîchit prix et stock des produits déjà importés, sans reimporter le catalogue. */
    @PostMapping("/{id}/sync/prices")
    @Secured({"ROLE_ADMIN", "ROLE_COMMERCIAL"})
    public String syncPrices(@PathVariable Long id, RedirectAttributes ra) {
        customerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Fournisseur introuvable : " + id));
        IngramSyncTracker.JobState state = syncTracker.get(id);
        if (state.status == IngramSyncTracker.SyncStatus.RUNNING) {
            ra.addFlashAttribute("errorMessage", "Une synchronisation est déjà en cours.");
            return "redirect:/intra/suppliers/" + id;
        }
        ingramMicroService.refreshPricesAndStockAsync(id, null);
        ra.addFlashAttribute("successMessage",
                "Mise à jour prix/stock démarrée en arrière-plan. La progression apparaît ci-dessous.");
        return "redirect:/intra/suppliers/" + id;
    }

    /** Fragment HTMX — progression de la sync complète Ingram. */
    @GetMapping("/{id}/sync/ingram/status")
    public String syncIngramStatus(@PathVariable Long id, Model model) {
        model.addAttribute("syncState",  syncTracker.get(id));
        model.addAttribute("supplierId", id);
        return "intra/suppliers/ingram-sync-status :: status";
    }

    // ── Ajouter un produit au catalogue fournisseur ───────────────────────────

    @PostMapping("/{id}/products")
    public String addProduct(@PathVariable Long id,
                             @RequestParam Long productId,
                             @RequestParam(required = false) String supplierReference,
                             @RequestParam(required = false) String supplierName,
                             @RequestParam BigDecimal purchasePrice,
                             @RequestParam(required = false) Integer stockQuantity,
                             RedirectAttributes ra) {
        Customer supplier = customerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Fournisseur introuvable : " + id));
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Produit introuvable : " + productId));

        SupplierProduct sp = supplierProductRepository.findBySupplierIdAndProductId(id, productId)
                .orElse(null);

        if (sp == null) {
            sp = new SupplierProduct();
            sp.setSupplier(supplier);
            sp.setProduct(product);
        }
        sp.setSupplierReference(supplierReference);
        sp.setSupplierName(supplierName);
        sp.setPurchasePrice(purchasePrice);
        sp.setStockQuantity(stockQuantity);
        supplierProductRepository.save(sp);

        ra.addFlashAttribute("successMessage", "Produit ajouté au catalogue fournisseur.");
        return "redirect:/intra/suppliers/" + id;
    }

    // ── Mettre à jour le PA manuellement ─────────────────────────────────────

    @PostMapping("/{id}/products/{spId}/update-pa")
    @Secured({"ROLE_ADMIN", "ROLE_COMMERCIAL"})
    @Transactional
    public String updatePa(@PathVariable Long id,
                           @PathVariable Long spId,
                           @RequestParam BigDecimal purchasePrice,
                           @RequestParam(defaultValue = "0") int page,
                           @RequestParam(defaultValue = "10") int size,
                           @RequestParam(required = false) String search,
                           RedirectAttributes ra) {
        SupplierProduct sp = supplierProductRepository.findById(spId).orElse(null);
        if (sp == null) {
            ra.addFlashAttribute("errorMessage", "Produit introuvable.");
        } else {
            sp.setPurchasePrice(purchasePrice);
            supplierProductRepository.save(sp);
            ra.addFlashAttribute("successMessage",
                    "PA enregistré : " + purchasePrice + " € — " + sp.getProduct().getName());
        }
        String redirect = "redirect:/intra/suppliers/" + id + "?page=" + page + "&size=" + size;
        if (search != null && !search.isBlank()) redirect += "&search=" + search;
        return redirect;
    }

    // ── Retirer un produit ────────────────────────────────────────────────────

    @PostMapping("/{id}/products/{spId}/delete")
    public String deleteProduct(@PathVariable Long id,
                                @PathVariable Long spId,
                                RedirectAttributes ra) {
        supplierProductRepository.findById(spId).ifPresent(sp -> {
            if (sp.getSupplier().getId().equals(id)) {
                supplierProductRepository.delete(sp);
            }
        });
        ra.addFlashAttribute("successMessage", "Produit retiré du catalogue fournisseur.");
        return "redirect:/intra/suppliers/" + id;
    }

    // ── Publier en boutique ───────────────────────────────────────────────────

    @PostMapping("/{id}/products/{spId}/publish-to-shop")
    @Secured({"ROLE_ADMIN", "ROLE_COMMERCIAL"})
    public String publishToShop(@PathVariable Long id,
                                @PathVariable Long spId,
                                @RequestParam(required = false) Long ruleId,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "10") int size,
                                @RequestParam(required = false) String search,
                                RedirectAttributes ra) {
        SupplierProduct sp = supplierProductRepository.findById(spId)
                .filter(s -> s.getSupplier().getId().equals(id))
                .orElseThrow(() -> new IllegalArgumentException("Produit fournisseur introuvable"));

        PricingRule rule = ruleId != null
                ? pricingRuleRepository.findById(ruleId).orElse(null) : null;
        UpsertResult result = upsertShopProduct(sp, rule);
        String ruleLabel = rule != null ? " [règle : " + rule.getName() + "]" : " [sans règle]";
        String priceInfo;
        if (result.shopProduct().getPriceOverride() != null) {
            priceInfo = " — " + result.shopProduct().getPriceOverride() + " € HT";
        } else if (rule != null && sp.getPurchasePrice() == null) {
            priceInfo = " — PA=" + sp.getPurchasePrice() + " (manquant)";
        } else {
            priceInfo = " — prix à définir";
        }
        ra.addFlashAttribute("successMessage",
                "« " + result.shopProduct().getDisplayTitle() + " » "
                + (result.created() ? "publié" : "mis à jour") + " en boutique"
                + ruleLabel + " PA=" + sp.getPurchasePrice() + priceInfo + ".");

        String redirect = "redirect:/intra/suppliers/" + id + "?page=" + page + "&size=" + size;
        if (search != null && !search.isBlank()) redirect += "&search=" + search;
        return redirect;
    }

    @PostMapping("/{id}/products/publish-bulk")
    @Secured({"ROLE_ADMIN", "ROLE_COMMERCIAL"})
    public String publishBulk(@PathVariable Long id,
                              @RequestParam(required = false) List<Long> spIds,
                              @RequestParam(required = false) Long ruleId,
                              @RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "10") int size,
                              @RequestParam(required = false) String search,
                              RedirectAttributes ra) {
        if (spIds == null || spIds.isEmpty()) {
            ra.addFlashAttribute("errorMessage", "Aucun produit sélectionné.");
        } else {
            PricingRule rule = ruleId != null
                    ? pricingRuleRepository.findById(ruleId).orElse(null) : null;
            int created = 0, updated = 0, noPa = 0;
            for (Long spId : spIds) {
                SupplierProduct sp = supplierProductRepository.findById(spId).orElse(null);
                if (sp == null || !sp.getSupplier().getId().equals(id)) continue;
                if (rule != null && sp.getPurchasePrice() == null) noPa++;
                UpsertResult r = upsertShopProduct(sp, rule);
                if (r.created()) created++; else updated++;
            }
            String ruleInfo = rule != null ? " avec « " + rule.getName() + " »" : "";
            String msg = created + " publié(s)" + ruleInfo;
            if (updated > 0) msg += ", " + updated + " mis à jour";
            if (noPa > 0) msg += " (" + noPa + " sans PA — prix non calculé)";
            ra.addFlashAttribute("successMessage", msg + ".");
        }
        String redirect = "redirect:/intra/suppliers/" + id + "?page=" + page + "&size=" + size;
        if (search != null && !search.isBlank()) redirect += "&search=" + search;
        return redirect;
    }

    private record UpsertResult(ShopProduct shopProduct, boolean created) {}

    private UpsertResult upsertShopProduct(SupplierProduct sp, PricingRule rule) {
        ShopProduct shopProd = shopProductRepository.findByProductId(sp.getProduct().getId())
                .orElse(null);
        boolean isNew = (shopProd == null);

        if (isNew) {
            shopProd = new ShopProduct();
            shopProd.setProduct(sp.getProduct());
            String title = sp.getSupplierName() != null ? sp.getSupplierName() : sp.getProduct().getName();
            shopProd.setShopTitle(title);
            shopProd.setSlug(uniqueSlug(title));
        }

        shopProd.setBrand(sp.getVendorName());
        shopProd.setShopCategory(sp.getSubCategory());
        shopProd.setStockEnabled(true);
        shopProd.setStock(sp.getStockQuantity() != null ? sp.getStockQuantity() : 0);
        shopProd.setPublished(true);

        if (rule != null && sp.getPurchasePrice() != null) {
            BigDecimal computed = rule.applyTo(sp.getPurchasePrice());
            BigDecimal oldPrice = shopProd.getPriceOverride() != null
                    ? shopProd.getPriceOverride() : sp.getProduct().getPriceHT();
            if (oldPrice == null || oldPrice.compareTo(computed) != 0) {
                String user = SecurityContextHolder.getContext().getAuthentication().getName();
                priceHistoryRepository.save(ShopPriceHistory.of(
                        sp.getProduct().getId(), oldPrice, computed, "Règle: " + rule.getName(), user));
            }
            shopProd.setPriceOverride(computed);
            sp.getProduct().setPriceHT(computed);
            productRepository.save(sp.getProduct());
        }

        shopProductRepository.save(shopProd);
        return new UpsertResult(shopProd, isNew);
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

    private static String trimOrNull(String s) {
        return (s != null && !s.isBlank()) ? s.trim() : null;
    }
}
