package com.logicielpourtous.site.features.intra;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.auth.DemandesSocieteConnueService;
import com.logicielpourtous.site.features.dashboard.UserDashboardConfig;
import com.logicielpourtous.site.features.dashboard.UserDashboardConfigRepository;
import com.logicielpourtous.site.features.request.Request;
import com.logicielpourtous.site.features.request.RequestRepository;
import com.logicielpourtous.site.features.request.RequestStatus;
import com.logicielpourtous.site.features.shop.ShopOrder;
import com.logicielpourtous.site.features.shop.ShopOrderRepository;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/intra")
@Secured({"ROLE_ADMIN", "ROLE_USER", "ROLE_COMMERCIAL", "ROLE_TECHNICIEN",
          "ROLE_MARKETING", "ROLE_COMPTABLE"})
public class IntraController {

    private final IntraWidgetService           widgetService;
    private final UserDashboardConfigRepository configRepository;
    private final RequestRepository            requestRepository;
    private final ShopOrderRepository          shopOrderRepository;
    private final DemandesSocieteConnueService demandesSocieteConnueService;

    public IntraController(IntraWidgetService widgetService,
                           UserDashboardConfigRepository configRepository,
                           RequestRepository requestRepository,
                           ShopOrderRepository shopOrderRepository,
                           DemandesSocieteConnueService demandesSocieteConnueService) {
        this.widgetService      = widgetService;
        this.configRepository   = configRepository;
        this.requestRepository  = requestRepository;
        this.shopOrderRepository = shopOrderRepository;
        this.demandesSocieteConnueService = demandesSocieteConnueService;
    }

    // ── Accueil ESN (dashboard global configurable) ───────────────────────────

    @GetMapping({"", "/"})
    public String accueil(@AuthenticationPrincipal CustomUserDetails user,
                          Authentication auth, Model model) {
        if (auth == null || user == null) return "redirect:/login";
        Set<String> roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        UserDashboardConfig config = configRepository.findById(user.getUserId()).orElse(null);

        model.addAttribute("intraWidgets",          widgetService.activeWidgets(config, roles));
        model.addAttribute("intraWidgetsAvailable", widgetService.availableWidgets(roles));
        model.addAttribute("intraWidgetConfig",     config);
        model.addAttribute("currentDashboard",      "accueil");
        // Demandes « societe connue » : reservees aux administrateurs (comme l'ecran), compteur de leur perimetre
        if (roles.contains("ROLE_ADMIN")) {
            model.addAttribute("demandesSocieteConnueNonTraitees",
                    demandesSocieteConnueService.compter(user.getTenant()));
        }

        List<Request> recentTickets = requestRepository
                .findByStatusInOrderByCreatedAtDesc(List.of(RequestStatus.NEW, RequestStatus.IN_PROGRESS))
                .stream().limit(5).toList();
        List<ShopOrder> pendingOrders = shopOrderRepository
                .findByStatusOrderByCreatedAtDesc(ShopOrder.OrderStatus.PENDING_VALIDATION)
                .stream().limit(5).toList();

        model.addAttribute("recentTickets",  recentTickets);
        model.addAttribute("pendingOrders",  pendingOrders);
        return "intra/dashboards/accueil";
    }

    @PostMapping("/accueil/widgets")
    public String saveWidgets(@AuthenticationPrincipal CustomUserDetails user,
                              @RequestParam(required = false) List<String> widgets,
                              RedirectAttributes ra) {
        UserDashboardConfig config = configRepository.findById(user.getUserId())
                .orElseGet(() -> {
                    var c = new UserDashboardConfig();
                    c.setUserId(user.getUserId());
                    return c;
                });
        config.setIntraWidgetCodes(widgets == null ? "" : String.join(",", widgets));
        configRepository.save(config);
        ra.addFlashAttribute("successMessage", "Tableau de bord personnalisé.");
        return "redirect:/intra";
    }

    // ── Dashboard legacy ──────────────────────────────────────────────────────

    @GetMapping("/dashboard")
    public String dashboardRoot() {
        return "redirect:/intra/dashboard/commercial";
    }

    @GetMapping("/dashboard/legacy")
    public String dashboard(Model model) {
        model.addAttribute("widgets", widgetService.activeWidgets(null, Set.of("ROLE_ADMIN")));

        List<Request> recentTickets = requestRepository
                .findByStatusInOrderByCreatedAtDesc(
                        List.of(RequestStatus.NEW, RequestStatus.IN_PROGRESS))
                .stream().limit(5).toList();
        List<ShopOrder> pendingOrders = shopOrderRepository
                .findByStatusOrderByCreatedAtDesc(ShopOrder.OrderStatus.PENDING_VALIDATION)
                .stream().limit(5).toList();

        model.addAttribute("recentTickets",  recentTickets);
        model.addAttribute("pendingOrders",  pendingOrders);
        return "intra/dashboard";
    }
}
