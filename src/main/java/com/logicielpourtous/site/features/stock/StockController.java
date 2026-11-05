package com.logicielpourtous.site.features.stock;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.customers.Site;
import com.logicielpourtous.site.features.customers.SiteRepository;
import com.logicielpourtous.site.features.product.Product;
import com.logicielpourtous.site.features.product.ProductRepository;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/intra/stock")
public class StockController {

    private final StockItemRepository     stockItemRepository;
    private final StockMovementRepository movementRepository;
    private final SiteRepository          siteRepository;
    private final ProductRepository       productRepository;
    private final UserRepository          userRepository;

    public StockController(StockItemRepository stockItemRepository,
                           StockMovementRepository movementRepository,
                           SiteRepository siteRepository,
                           ProductRepository productRepository,
                           UserRepository userRepository) {
        this.stockItemRepository = stockItemRepository;
        this.movementRepository  = movementRepository;
        this.siteRepository      = siteRepository;
        this.productRepository   = productRepository;
        this.userRepository      = userRepository;
    }

    // ── Vue globale ──────────────────────────────────────────────────────────

    @GetMapping
    public String overview(Model model) {
        List<Site> sites = siteRepository.findAll()
                .stream().filter(Site::isActive).sorted(Comparator.comparing(Site::getName)).toList();

        // Statistiques par site
        List<SiteStat> siteStats = sites.stream().map(s -> {
            SiteStat stat = new SiteStat();
            stat.site        = s;
            stat.references  = stockItemRepository.countBySiteId(s.getId());
            stat.totalQty    = stockItemRepository.sumQuantityBySiteId(s.getId());
            stat.lowStockCount = stockItemRepository.countLowStockBySiteId(s.getId());
            return stat;
        }).toList();

        List<StockItem> lowStockItems = stockItemRepository.findLowStock();

        model.addAttribute("siteStats",     siteStats);
        model.addAttribute("lowStockItems", lowStockItems);
        model.addAttribute("sites",         sites);
        model.addAttribute("products",      productRepository.findByActiveTrueOrderByNameAsc());
        return "intra/stock/list";
    }

    // ── Vue par site ──────────────────────────────────────────────────────────

    @GetMapping("/site/{siteId}")
    public String siteDetail(@PathVariable Long siteId, Model model) {
        Site site = siteRepository.findById(siteId)
                .orElseThrow(() -> new IllegalArgumentException("Site introuvable"));

        List<StockItem> items     = stockItemRepository.findBySiteIdWithProduct(siteId);
        List<StockMovement> moves = movementRepository.findBySiteIdOrderByCreatedAtDesc(siteId);

        model.addAttribute("site",     site);
        model.addAttribute("items",    items);
        model.addAttribute("moves",    moves.stream().limit(50).toList());
        model.addAttribute("products", productRepository.findByActiveTrueOrderByNameAsc());
        model.addAttribute("sites",    siteRepository.findAll().stream().filter(Site::isActive)
                                        .filter(s -> !s.getId().equals(siteId)).toList());
        model.addAttribute("movementTypes", StockMovement.MovementType.values());
        return "intra/stock/site";
    }

    // ── Entrée de stock ───────────────────────────────────────────────────────

    @PostMapping("/entry")
    public String entry(@RequestParam Long siteId,
                        @RequestParam Long productId,
                        @RequestParam int quantity,
                        @RequestParam(required = false) String reason,
                        @RequestParam(required = false) String reference,
                        @AuthenticationPrincipal CustomUserDetails principal,
                        RedirectAttributes ra) {
        if (quantity <= 0) { ra.addFlashAttribute("errorMessage", "La quantité doit être positive."); return "redirect:/intra/stock/site/" + siteId; }
        StockItem item = getOrCreate(siteId, productId);
        item.setQuantity(item.getQuantity() + quantity);
        stockItemRepository.save(item);
        saveMovement(item, StockMovement.MovementType.ENTRY, quantity, reason, reference, principal);
        ra.addFlashAttribute("successMessage", quantity + " unité(s) ajoutée(s).");
        return "redirect:/intra/stock/site/" + siteId;
    }

    // ── Sortie de stock ───────────────────────────────────────────────────────

    @PostMapping("/exit")
    public String exit(@RequestParam Long siteId,
                       @RequestParam Long productId,
                       @RequestParam int quantity,
                       @RequestParam(required = false) String reason,
                       @RequestParam(required = false) String reference,
                       @AuthenticationPrincipal CustomUserDetails principal,
                       RedirectAttributes ra) {
        if (quantity <= 0) { ra.addFlashAttribute("errorMessage", "La quantité doit être positive."); return "redirect:/intra/stock/site/" + siteId; }
        StockItem item = getOrCreate(siteId, productId);
        if (item.getQuantity() < quantity) {
            ra.addFlashAttribute("errorMessage", "Stock insuffisant (" + item.getQuantity() + " disponible(s)).");
            return "redirect:/intra/stock/site/" + siteId;
        }
        item.setQuantity(item.getQuantity() - quantity);
        stockItemRepository.save(item);
        saveMovement(item, StockMovement.MovementType.EXIT, quantity, reason, reference, principal);
        ra.addFlashAttribute("successMessage", quantity + " unité(s) retirée(s).");
        return "redirect:/intra/stock/site/" + siteId;
    }

    // ── Ajustement (inventaire) ───────────────────────────────────────────────

    @PostMapping("/adjust")
    public String adjust(@RequestParam Long siteId,
                         @RequestParam Long productId,
                         @RequestParam int newQuantity,
                         @RequestParam(required = false) String reason,
                         @AuthenticationPrincipal CustomUserDetails principal,
                         RedirectAttributes ra) {
        if (newQuantity < 0) { ra.addFlashAttribute("errorMessage", "La quantité ne peut pas être négative."); return "redirect:/intra/stock/site/" + siteId; }
        StockItem item = getOrCreate(siteId, productId);
        int delta = Math.abs(newQuantity - item.getQuantity());
        item.setQuantity(newQuantity);
        stockItemRepository.save(item);
        if (delta > 0) saveMovement(item, StockMovement.MovementType.ADJUSTMENT, delta,
                reason != null ? reason : "Inventaire", null, principal);
        ra.addFlashAttribute("successMessage", "Stock ajusté à " + newQuantity + " unité(s).");
        return "redirect:/intra/stock/site/" + siteId;
    }

    // ── Mise à jour seuil + emplacement ──────────────────────────────────────

    @PostMapping("/settings")
    public String settings(@RequestParam Long stockItemId,
                            @RequestParam int minQuantity,
                            @RequestParam(required = false) String locationLabel,
                            RedirectAttributes ra) {
        stockItemRepository.findById(stockItemId).ifPresent(item -> {
            item.setMinQuantity(Math.max(0, minQuantity));
            item.setLocationLabel(locationLabel);
            stockItemRepository.save(item);
        });
        ra.addFlashAttribute("successMessage", "Paramètres mis à jour.");
        return stockItemRepository.findById(stockItemId)
                .map(i -> "redirect:/intra/stock/site/" + i.getSite().getId())
                .orElse("redirect:/intra/stock");
    }

    // ── Transfert inter-sites ─────────────────────────────────────────────────

    @PostMapping("/transfer")
    public String transfer(@RequestParam Long fromSiteId,
                           @RequestParam Long toSiteId,
                           @RequestParam Long productId,
                           @RequestParam int quantity,
                           @RequestParam(required = false) String reason,
                           @AuthenticationPrincipal CustomUserDetails principal,
                           RedirectAttributes ra) {
        if (quantity <= 0) { ra.addFlashAttribute("errorMessage", "La quantité doit être positive."); return "redirect:/intra/stock/site/" + fromSiteId; }
        StockItem from = getOrCreate(fromSiteId, productId);
        if (from.getQuantity() < quantity) {
            ra.addFlashAttribute("errorMessage", "Stock insuffisant (" + from.getQuantity() + " disponible(s)).");
            return "redirect:/intra/stock/site/" + fromSiteId;
        }
        from.setQuantity(from.getQuantity() - quantity);
        stockItemRepository.save(from);
        saveMovement(from, StockMovement.MovementType.TRANSFER_OUT, quantity,
                reason != null ? reason : "Transfert vers site #" + toSiteId, null, principal);

        StockItem to = getOrCreate(toSiteId, productId);
        to.setQuantity(to.getQuantity() + quantity);
        stockItemRepository.save(to);
        saveMovement(to, StockMovement.MovementType.TRANSFER_IN, quantity,
                reason != null ? reason : "Transfert depuis site #" + fromSiteId, null, principal);

        ra.addFlashAttribute("successMessage", quantity + " unité(s) transférée(s).");
        return "redirect:/intra/stock/site/" + fromSiteId;
    }

    // ── Historique global ─────────────────────────────────────────────────────

    @GetMapping("/movements")
    public String movements(Model model) {
        model.addAttribute("movements", movementRepository.findAllWithDetails());
        return "intra/stock/movements";
    }

    // ── Utilitaires ───────────────────────────────────────────────────────────

    private StockItem getOrCreate(Long siteId, Long productId) {
        return stockItemRepository.findByProductIdAndSiteId(productId, siteId)
                .orElseGet(() -> {
                    StockItem item = new StockItem();
                    item.setSite(siteRepository.getReferenceById(siteId));
                    item.setProduct(productRepository.getReferenceById(productId));
                    return item;
                });
    }

    private void saveMovement(StockItem item, StockMovement.MovementType type,
                               int quantity, String reason, String reference,
                               CustomUserDetails principal) {
        StockMovement m = new StockMovement();
        m.setStockItem(item);
        m.setType(type);
        m.setQuantity(quantity);
        m.setQuantityAfter(item.getQuantity());
        m.setReason(reason);
        m.setReference(reference);
        if (principal != null) {
            userRepository.findById(principal.getUserId()).ifPresent(m::setCreatedBy);
        }
        movementRepository.save(m);
    }

    // ── DTO interne ───────────────────────────────────────────────────────────

    public static class SiteStat {
        public Site site;
        public long references;
        public long totalQty;
        public long lowStockCount;
    }
}
