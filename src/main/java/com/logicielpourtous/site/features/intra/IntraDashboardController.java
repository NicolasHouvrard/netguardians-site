package com.logicielpourtous.site.features.intra;

import com.logicielpourtous.site.config.ModuleSecurityInterceptor;
import com.logicielpourtous.site.features.module.AdminModule;
import com.logicielpourtous.site.features.module.AdminModuleRepository;
import com.logicielpourtous.site.features.module.RouteService;
import com.logicielpourtous.site.features.role.RoleRepository;
import com.logicielpourtous.site.features.user.UserRepository;
import com.logicielpourtous.site.features.auth.CustomUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.transaction.Transactional;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/intra/dashboard")
@Secured({"ROLE_ADMIN", "ROLE_USER", "ROLE_COMMERCIAL", "ROLE_TECHNICIEN",
          "ROLE_MARKETING", "ROLE_COMPTABLE"})
public class IntraDashboardController {

    private final AdminModuleRepository           moduleRepository;
    private final UserRepository                  userRepository;
    private final RoleRepository                  roleRepository;
    private final IntraDashboardRepository        dashboardRepository;
    private final DashboardWidgetLayoutRepository widgetLayoutRepository;
    private final IntraWidgetService              widgetService;
    private final ModuleSecurityInterceptor       moduleSecurityInterceptor;
    private final RouteService                    routeService;

    public IntraDashboardController(AdminModuleRepository moduleRepository,
                                    UserRepository userRepository,
                                    RoleRepository roleRepository,
                                    IntraDashboardRepository dashboardRepository,
                                    DashboardWidgetLayoutRepository widgetLayoutRepository,
                                    IntraWidgetService widgetService,
                                    ModuleSecurityInterceptor moduleSecurityInterceptor,
                                    RouteService routeService) {
        this.moduleRepository           = moduleRepository;
        this.userRepository             = userRepository;
        this.roleRepository             = roleRepository;
        this.dashboardRepository        = dashboardRepository;
        this.widgetLayoutRepository     = widgetLayoutRepository;
        this.widgetService              = widgetService;
        this.moduleSecurityInterceptor  = moduleSecurityInterceptor;
        this.routeService               = routeService;
    }

    // ── Configuration (page admin spécifique) ────────────────────────────────

    @GetMapping("/config")
    public String config(Model model) {
        model.addAttribute("currentDashboard", "config");
        model.addAttribute("nbModules",       moduleRepository.count());
        model.addAttribute("nbUtilisateurs",  userRepository.count());
        model.addAttribute("nbRoles",         roleRepository.count());
        return "intra/dashboards/config";
    }

    // ── Gestionnaire de catégories de dashboard ───────────────────────────────

    private static final List<String> DASHBOARD_CATEGORIES =
            List.of("Commercial", "Comptabilité", "Marketing", "Support", "Configuration", "Autres");

    @GetMapping("/categories")
    public String categories(Model model) {
        model.addAttribute("currentDashboard", "config");
        Map<String, List<AdminModule>> byCategory = new LinkedHashMap<>();
        for (String cat : DASHBOARD_CATEGORIES) byCategory.put(cat, new java.util.ArrayList<>());
        byCategory.put("(Sans catégorie)", new java.util.ArrayList<>());

        moduleRepository.findAllByOrderByCategoryAscDisplayOrderAsc().forEach(m -> {
            String cat = m.getCategory();
            if (cat == null || cat.isBlank()) byCategory.get("(Sans catégorie)").add(m);
            else byCategory.computeIfAbsent(cat, k -> new java.util.ArrayList<>()).add(m);
        });

        model.addAttribute("byCategory",          byCategory);
        model.addAttribute("dashboardCategories", DASHBOARD_CATEGORIES);
        model.addAttribute("allRoles",            roleRepository.findAll());
        model.addAttribute("availableRoutes",     routeService.getModuleBasePaths());
        return "intra/dashboards/categories";
    }

    // ── Module CRUD (admin only, depuis la page catégories) ──────────────────

    @GetMapping("/categories/module/new")
    @Secured("ROLE_ADMIN")
    public String newModuleForm(Model model) {
        AdminModule m = new AdminModule();
        m.setColor("#0d6efd");
        m.setIcon("fas fa-cube");
        m.setDisplayOrder(10);
        m.setAudience("ESN");
        m.setActive(true);
        addModuleFormModel(model, m);
        return "intra/dashboards/module-form";
    }

    @GetMapping("/categories/module/{id}/edit")
    @Secured("ROLE_ADMIN")
    public String editModuleForm(@PathVariable Long id, Model model) {
        AdminModule m = moduleRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        addModuleFormModel(model, m);
        return "intra/dashboards/module-form";
    }

    private void addModuleFormModel(Model model, AdminModule m) {
        List<String> routes = routeService.getModuleBasePaths();
        model.addAttribute("adminModule",         m);
        model.addAttribute("allRoles",            roleRepository.findAll());
        model.addAttribute("dashboardCategories", DASHBOARD_CATEGORIES);
        model.addAttribute("availableRoutes",     routes);
        model.addAttribute("currentDashboard",    "config");
    }

    @PostMapping("/categories/module/save")
    @Secured("ROLE_ADMIN")
    @Transactional
    public String saveModule(@ModelAttribute AdminModule adminModule,
                             @RequestParam(required = false) List<String> rolesRequired,
                             RedirectAttributes attrs) {
        if (adminModule.getId() == null && adminModule.getUrl() != null) {
            moduleRepository.findFirstByUrl(adminModule.getUrl())
                    .ifPresent(existing -> adminModule.setId(existing.getId()));
        }
        if (rolesRequired != null && !rolesRequired.isEmpty()) {
            adminModule.setRoleRequired(String.join(",", rolesRequired));
        } else {
            adminModule.setRoleRequired(null);
        }
        moduleRepository.save(adminModule);
        moduleSecurityInterceptor.clearCache();
        attrs.addFlashAttribute("successMessage", "Module enregistré.");
        return "redirect:/intra/dashboard/categories";
    }

    @PostMapping("/categories/module/{id}/delete")
    @Secured("ROLE_ADMIN")
    public String deleteModule(@PathVariable Long id, RedirectAttributes attrs) {
        moduleRepository.deleteById(id);
        moduleSecurityInterceptor.clearCache();
        attrs.addFlashAttribute("successMessage", "Module supprimé.");
        return "redirect:/intra/dashboard/categories";
    }

    // ── Dashboard dynamique (slugs non couverts par les routes spécifiques) ───

    @GetMapping("/{slug}")
    public String dynamicDashboard(@PathVariable String slug,
                                   @AuthenticationPrincipal CustomUserDetails user,
                                   Authentication auth, Model model) {
        IntraDashboard db = dashboardRepository.findBySlugAndActiveTrue(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Dashboard introuvable : " + slug));

        if (auth == null || user == null) return "redirect:/login";

        Set<String> roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        if (db.getRoleRequired() != null
                && !roles.contains("ROLE_ADMIN")
                && !roles.contains(db.getRoleRequired())) {
            return "redirect:/intra";
        }

        model.addAttribute("currentDashboard", slug);
        model.addAttribute("currentDashboardObj", db);

        // Layouts Gridstack (widgets loaded async via HTMX — no widgetDataMap needed)
        List<DashboardWidgetLayout> layouts = widgetLayoutRepository.findByDashboardOrderByPositionAsc(db);

        // Widgets disponibles à ajouter (non encore présents sur ce dashboard)
        Set<String> usedKeys = layouts.stream().map(DashboardWidgetLayout::getWidgetType).collect(Collectors.toSet());
        List<IntraWidget> availableWidgets = widgetService.availableWidgets(roles).stream()
                .filter(w -> !usedKeys.contains(w.getWidgetKey()))
                .toList();

        model.addAttribute("widgetLayouts",    layouts);
        model.addAttribute("availableWidgets", availableWidgets);

        return "intra/dashboard-dynamic";
    }

    /** Sauvegarde la disposition Gridstack (x, y, w, h par widget). */
    @PostMapping("/{slug}/layout/save")
    @Secured("ROLE_ADMIN")
    @Transactional
    public String saveLayout(@PathVariable String slug,
                             @RequestParam(required = false) List<String> keys,
                             @RequestParam(required = false) List<Integer> xs,
                             @RequestParam(required = false) List<Integer> ys,
                             @RequestParam(required = false) List<Integer> ws,
                             @RequestParam(required = false) List<Integer> hs,
                             RedirectAttributes ra) {
        IntraDashboard db = dashboardRepository.findBySlugAndActiveTrue(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        widgetLayoutRepository.deleteByDashboardId(db.getId());
        if (keys != null) {
            for (int i = 0; i < keys.size(); i++) {
                String key = keys.get(i);
                if (key == null || key.isBlank()) continue;
                DashboardWidgetLayout l = new DashboardWidgetLayout();
                l.setDashboard(db);
                l.setWidgetType(key);
                l.setPosition(i + 1);
                int w = safe(ws, i, 3);
                l.setColSize(w);
                l.setGsX(safe(xs, i, null));
                l.setGsY(safe(ys, i, null));
                l.setGsW(w);
                l.setGsH(safe(hs, i, 2));
                widgetLayoutRepository.save(l);
            }
        }
        ra.addFlashAttribute("successMessage", "Disposition sauvegardée.");
        return "redirect:/intra/dashboard/" + slug + "?edit=1";
    }

    /** Ajoute un widget au dashboard avec taille par défaut selon son type. */
    @PostMapping("/{slug}/widget/add")
    @Secured("ROLE_ADMIN")
    @Transactional
    public String addWidget(@PathVariable String slug,
                            @RequestParam String widgetType,
                            RedirectAttributes ra) {
        IntraDashboard db = dashboardRepository.findBySlugAndActiveTrue(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (widgetLayoutRepository.existsByDashboardAndWidgetType(db, widgetType)) {
            ra.addFlashAttribute("errorMessage", "Ce widget est déjà présent sur le dashboard.");
            return "redirect:/intra/dashboard/" + slug;
        }
        boolean isList  = widgetType.endsWith("_LIST");
        boolean isMulti = widgetType.startsWith("FAC_CLI") || widgetType.startsWith("FAC_FOU");
        int defaultW = isList ? 6 : isMulti ? 4 : 3;
        int defaultH = isList ? 5 : 2;

        DashboardWidgetLayout l = new DashboardWidgetLayout();
        l.setDashboard(db);
        l.setWidgetType(widgetType);
        l.setPosition(widgetLayoutRepository.findByDashboardOrderByPositionAsc(db).size() + 1);
        l.setColSize(defaultW);
        l.setGsW(defaultW);
        l.setGsH(defaultH);
        widgetLayoutRepository.save(l);

        ra.addFlashAttribute("successMessage", "Widget ajouté.");
        return "redirect:/intra/dashboard/" + slug + "?edit=1";
    }

    /** Retire un widget du dashboard. */
    @PostMapping("/{slug}/widget/remove")
    @Secured("ROLE_ADMIN")
    @Transactional
    public String removeWidget(@PathVariable String slug,
                               @RequestParam String widgetType,
                               RedirectAttributes ra) {
        IntraDashboard db = dashboardRepository.findBySlugAndActiveTrue(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        widgetLayoutRepository.findByDashboardOrderByPositionAsc(db).stream()
                .filter(l -> l.getWidgetType().equals(widgetType))
                .findFirst()
                .ifPresent(widgetLayoutRepository::delete);
        ra.addFlashAttribute("successMessage", "Widget retiré.");
        return "redirect:/intra/dashboard/" + slug + "?edit=1";
    }

    private static <T> T safe(List<T> list, int i, T def) {
        return (list != null && i < list.size() && list.get(i) != null) ? list.get(i) : def;
    }

    @PostMapping("/categories")
    public String saveCategories(@RequestParam List<Long> moduleIds,
                                  @RequestParam List<String> moduleCategories,
                                  RedirectAttributes attrs) {
        for (int i = 0; i < moduleIds.size(); i++) {
            final int idx = i;
            moduleRepository.findById(moduleIds.get(idx)).ifPresent(m -> {
                m.setCategory(moduleCategories.get(idx));
                moduleRepository.save(m);
            });
        }
        attrs.addFlashAttribute("successMessage", "Catégories mises à jour.");
        return "redirect:/intra/dashboard/categories";
    }
}
