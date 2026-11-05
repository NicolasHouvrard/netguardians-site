package com.logicielpourtous.site.features.intra;

import com.logicielpourtous.site.features.module.AdminModule;
import com.logicielpourtous.site.features.module.AdminModuleRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import jakarta.transaction.Transactional;
import org.springframework.security.access.annotation.Secured;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/intra/dashboards")
@Secured("ROLE_ADMIN")
public class IntraDashboardManagerController {

    private final IntraDashboardRepository       dashboardRepo;
    private final DashboardModuleLinkRepository  moduleLinkRepo;
    private final DashboardWidgetLayoutRepository widgetLayoutRepo;
    private final AdminModuleRepository          moduleRepo;
    private final IntraWidgetRepository          widgetRepo;

    public IntraDashboardManagerController(IntraDashboardRepository dashboardRepo,
                                           DashboardModuleLinkRepository moduleLinkRepo,
                                           DashboardWidgetLayoutRepository widgetLayoutRepo,
                                           AdminModuleRepository moduleRepo,
                                           IntraWidgetRepository widgetRepo) {
        this.dashboardRepo    = dashboardRepo;
        this.moduleLinkRepo   = moduleLinkRepo;
        this.widgetLayoutRepo = widgetLayoutRepo;
        this.moduleRepo       = moduleRepo;
        this.widgetRepo       = widgetRepo;
    }

    // ── Liste ─────────────────────────────────────────────────────────────────

    @GetMapping
    public String list(Model model) {
        model.addAttribute("dashboards", dashboardRepo.findByActiveTrueOrderByDisplayOrderAsc());
        return "intra/manage/dashboards/list";
    }

    // ── Créer ─────────────────────────────────────────────────────────────────

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("dashboard", new IntraDashboard());
        return "intra/manage/dashboards/form";
    }

    @PostMapping("/save")
    public String save(@ModelAttribute IntraDashboard dashboard, RedirectAttributes ra) {
        if (dashboard.getSlug() == null || dashboard.getSlug().isBlank()) {
            dashboard.setSlug(slugify(dashboard.getName()));
        }
        dashboardRepo.save(dashboard);
        ra.addFlashAttribute("successMessage", "Dashboard \"" + dashboard.getName() + "\" enregistré.");
        return "redirect:/intra/dashboards";
    }

    // ── Éditer ────────────────────────────────────────────────────────────────

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        IntraDashboard d = dashboardRepo.findById(id).orElseThrow();
        model.addAttribute("dashboard", d);
        return "intra/manage/dashboards/form";
    }

    // ── Supprimer ─────────────────────────────────────────────────────────────

    @PostMapping("/{id}/delete")
    @Transactional
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        dashboardRepo.findById(id).ifPresent(d -> {
            d.setActive(false);
            dashboardRepo.save(d);
        });
        ra.addFlashAttribute("successMessage", "Dashboard désactivé.");
        return "redirect:/intra/dashboards";
    }

    // ── Modules ───────────────────────────────────────────────────────────────

    @GetMapping("/{id}/modules")
    public String modulesPage(@PathVariable Long id, Model model) {
        IntraDashboard d = dashboardRepo.findById(id).orElseThrow();
        List<DashboardModuleLink> links = moduleLinkRepo.findByDashboardOrderByDisplayOrderAsc(d);
        List<Long> linkedIds = links.stream()
                .map(l -> l.getModule().getId()).toList();
        List<AdminModule> available = moduleRepo.findAllByOrderByCategoryAscDisplayOrderAsc()
                .stream().filter(m -> !linkedIds.contains(m.getId()))
                .sorted(java.util.Comparator.comparing(m -> m.getTitle() != null ? m.getTitle().toLowerCase() : ""))
                .toList();

        // Grouper par subCategory (ordre = premier displayOrder du groupe)
        Map<String, List<DashboardModuleLink>> groupedLinks = new LinkedHashMap<>();
        for (DashboardModuleLink link : links) {
            String group = (link.getSubCategory() != null && !link.getSubCategory().isBlank())
                           ? link.getSubCategory() : "Général";
            groupedLinks.computeIfAbsent(group, k -> new ArrayList<>()).add(link);
        }

        model.addAttribute("dashboard",    d);
        model.addAttribute("links",        links);
        model.addAttribute("groupedLinks", groupedLinks);
        model.addAttribute("available",    available);
        return "intra/manage/dashboards/modules";
    }

    @PostMapping("/{id}/modules/add")
    @Transactional
    public String addModule(@PathVariable Long id,
                            @RequestParam Long moduleId,
                            @RequestParam(required = false) String subCategory,
                            RedirectAttributes ra) {
        IntraDashboard d = dashboardRepo.findById(id).orElseThrow();
        AdminModule m    = moduleRepo.findById(moduleId).orElseThrow();

        DashboardModuleLink link = new DashboardModuleLink();
        link.setDashboard(d);
        link.setModule(m);
        link.setSubCategory(subCategory != null && !subCategory.isBlank() ? subCategory : "Général");
        link.setDisplayOrder(moduleLinkRepo.findByDashboardOrderByDisplayOrderAsc(d).size() + 1);
        moduleLinkRepo.save(link);

        ra.addFlashAttribute("successMessage", "Module ajouté.");
        return "redirect:/intra/dashboards/" + id + "/modules";
    }

    @PostMapping("/modules/{linkId}/update")
    @Transactional
    public String updateLink(@PathVariable Long linkId,
                             @RequestParam(required = false) String subCategory,
                             @RequestParam(required = false) Integer displayOrder,
                             RedirectAttributes ra) {
        var link = moduleLinkRepo.findById(linkId).orElse(null);
        if (link == null) return "redirect:/intra/dashboards";
        Long dashId = link.getDashboard().getId();
        if (subCategory != null) link.setSubCategory(subCategory);
        if (displayOrder != null) link.setDisplayOrder(displayOrder);
        moduleLinkRepo.save(link);
        ra.addFlashAttribute("successMessage", "Mis à jour.");
        return "redirect:/intra/dashboards/" + dashId + "/modules";
    }

    @PostMapping("/modules/{linkId}/delete")
    @Transactional
    public String removeModule(@PathVariable Long linkId, RedirectAttributes ra) {
        var link = moduleLinkRepo.findById(linkId).orElse(null);
        if (link == null) return "redirect:/intra/dashboards";
        Long dashId = link.getDashboard().getId();
        moduleLinkRepo.delete(link);
        ra.addFlashAttribute("successMessage", "Module retiré.");
        return "redirect:/intra/dashboards/" + dashId + "/modules";
    }

    @PostMapping("/{id}/modules/reorder")
    @Transactional
    public String reorderModules(@PathVariable Long id,
                                  @RequestParam List<Long> linkIds,
                                  RedirectAttributes ra) {
        for (int i = 0; i < linkIds.size(); i++) {
            final int pos = i + 1;
            moduleLinkRepo.findById(linkIds.get(i))
                    .ifPresent(l -> { l.setDisplayOrder(pos); moduleLinkRepo.save(l); });
        }
        ra.addFlashAttribute("successMessage", "Ordre sauvegardé.");
        return "redirect:/intra/dashboards/" + id + "/modules";
    }

    // ── Widgets ───────────────────────────────────────────────────────────────

    @GetMapping("/{id}/widgets")
    public String widgetsPage(@PathVariable Long id, Model model) {
        IntraDashboard d = dashboardRepo.findById(id).orElseThrow();
        List<DashboardWidgetLayout> layouts = widgetLayoutRepo.findByDashboardOrderByPositionAsc(d);
        List<String> usedKeys = layouts.stream().map(DashboardWidgetLayout::getWidgetType).toList();
        List<IntraWidget> available = widgetRepo.findByActiveTrueOrderByDisplayOrderAsc().stream()
                .filter(w -> !usedKeys.contains(w.getWidgetKey()))
                .toList();

        // Map widgetKey → IntraWidget pour affichage des layouts existants
        Map<String, IntraWidget> widgetMap = widgetRepo.findByActiveTrueOrderByDisplayOrderAsc().stream()
                .collect(java.util.stream.Collectors.toMap(IntraWidget::getWidgetKey, w -> w, (a, b) -> a));

        model.addAttribute("dashboard",  d);
        model.addAttribute("layouts",    layouts);
        model.addAttribute("available",  available);
        model.addAttribute("widgetMap",  widgetMap);
        model.addAttribute("colSizes",   List.of(2, 3, 4, 6, 12));
        return "intra/manage/dashboards/widgets";
    }

    @PostMapping("/{id}/widgets/add")
    @Transactional
    public String addWidget(@PathVariable Long id,
                            @RequestParam String widgetType,
                            @RequestParam(defaultValue = "3") int colSize,
                            RedirectAttributes ra) {
        IntraDashboard d = dashboardRepo.findById(id).orElseThrow();
        if (!widgetRepo.existsByWidgetKey(widgetType)) {
            ra.addFlashAttribute("errorMessage", "Widget inconnu : " + widgetType);
            return "redirect:/intra/dashboards/" + id + "/widgets";
        }
        int nextPos = widgetLayoutRepo.findByDashboardOrderByPositionAsc(d).size() + 1;

        DashboardWidgetLayout layout = new DashboardWidgetLayout();
        layout.setDashboard(d);
        layout.setWidgetType(widgetType);
        layout.setPosition(nextPos);
        layout.setColSize(colSize);
        widgetLayoutRepo.save(layout);

        ra.addFlashAttribute("successMessage", "Widget ajouté.");
        return "redirect:/intra/dashboards/" + id + "/widgets";
    }

    @PostMapping("/widgets/{layoutId}/update")
    @Transactional
    public String updateWidget(@PathVariable Long layoutId,
                               @RequestParam(required = false) Integer colSize,
                               RedirectAttributes ra) {
        var layout = widgetLayoutRepo.findById(layoutId).orElse(null);
        if (layout == null) return "redirect:/intra/dashboards";
        Long dashId = layout.getDashboard().getId();
        if (colSize != null) layout.setColSize(colSize);
        widgetLayoutRepo.save(layout);
        ra.addFlashAttribute("successMessage", "Widget mis à jour.");
        return "redirect:/intra/dashboards/" + dashId + "/widgets";
    }

    @PostMapping("/widgets/{layoutId}/delete")
    @Transactional
    public String removeWidget(@PathVariable Long layoutId, RedirectAttributes ra) {
        var layout = widgetLayoutRepo.findById(layoutId).orElse(null);
        if (layout == null) return "redirect:/intra/dashboards";
        Long dashId = layout.getDashboard().getId();
        widgetLayoutRepo.delete(layout);
        ra.addFlashAttribute("successMessage", "Widget retiré.");
        return "redirect:/intra/dashboards/" + dashId + "/widgets";
    }

    @PostMapping("/{id}/widgets/reorder")
    @Transactional
    public String reorderWidgets(@PathVariable Long id,
                                  @RequestParam List<Long> layoutIds,
                                  RedirectAttributes ra) {
        for (int i = 0; i < layoutIds.size(); i++) {
            final int pos = i + 1;
            widgetLayoutRepo.findById(layoutIds.get(i))
                    .ifPresent(l -> { l.setPosition(pos); widgetLayoutRepo.save(l); });
        }
        ra.addFlashAttribute("successMessage", "Ordre sauvegardé.");
        return "redirect:/intra/dashboards/" + id + "/widgets";
    }

    // ── Utilitaire ────────────────────────────────────────────────────────────

    private static String slugify(String name) {
        if (name == null) return "dashboard-" + System.currentTimeMillis();
        return name.toLowerCase()
                   .replaceAll("[éèêë]", "e")
                   .replaceAll("[àâä]", "a")
                   .replaceAll("[ùûü]", "u")
                   .replaceAll("[ôö]", "o")
                   .replaceAll("[îï]", "i")
                   .replaceAll("[^a-z0-9]+", "-")
                   .replaceAll("^-|-$", "");
    }
}
