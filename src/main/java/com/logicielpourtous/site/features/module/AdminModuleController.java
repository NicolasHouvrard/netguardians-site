package com.logicielpourtous.site.features.module;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.logicielpourtous.site.config.ModuleSecurityInterceptor;
import com.logicielpourtous.site.features.role.RoleRepository;
import com.logicielpourtous.site.features.site.PageRepository;

import org.springframework.stereotype.Controller;

@Controller
@RequestMapping("/admin/settings/modules")
public class AdminModuleController {

    private final AdminModuleRepository moduleRepository;
private final RoleRepository roleRepository;
private final RouteService routeService;
private final ModuleSecurityInterceptor moduleSecurityInterceptor;

public AdminModuleController(AdminModuleRepository moduleRepository,
                              RoleRepository roleRepository,
                              RouteService routeService,
                              ModuleSecurityInterceptor moduleSecurityInterceptor) {
    this.moduleRepository = moduleRepository;
    this.roleRepository = roleRepository;
    this.routeService = routeService;
    this.moduleSecurityInterceptor = moduleSecurityInterceptor;
}
    // Liste des modules existants
    @GetMapping
    public String list(Model model) {
        model.addAttribute("listeModules", moduleRepository.findAllByOrderByCategoryAscDisplayOrderAsc());
        return "feature/module/list";
    }

    // Formulaire de création avec VALEURS PAR DÉFAUT
    @GetMapping("/new")
    public String create(Model model) {
        AdminModule module = new AdminModule();
        // Valeurs par défaut
        module.setColor("#4e73df"); // Bleu par défaut
        module.setIcon("fas fa-cube"); // Icône cube par défaut
        module.setDisplayOrder(10);
        module.setCategory("Général");
        
        model.addAttribute("adminModule", module);
        model.addAttribute("allRoles", roleRepository.findAll());
       model.addAttribute("availableRoutes", routeService.getControllerBasePaths());

        return "feature/module/form";
    }

    @PostMapping("/save")
    public String save(@ModelAttribute AdminModule adminModule, RedirectAttributes attrs) {
        // Si l'ID est null (nouveau module), vérifier si l'URL existe déjà pour éviter les doublons
        if (adminModule.getId() == null && adminModule.getUrl() != null) {
            moduleRepository.findFirstByUrl(adminModule.getUrl())
                .ifPresent(existing -> adminModule.setId(existing.getId()));
        }
        moduleRepository.save(adminModule);
        moduleSecurityInterceptor.clearCache();
        attrs.addFlashAttribute("successMessage", "Module enregistré avec succès !");
        return "redirect:/admin/settings/modules";
    }

    // Édition d'un module existant
    @GetMapping("/edit/{id}")
    public String edit(@PathVariable Long id, Model model) {
        AdminModule module = moduleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Module invalide"));
        model.addAttribute("adminModule", module);
        model.addAttribute("allRoles", roleRepository.findAll());
        model.addAttribute("availableRoutes", routeService.getControllerBasePaths());

        return "feature/module/form"; // Réutilise le même formulaire que pour la création
    }

    // Suppression
    @PostMapping("/delete/{id}")
    public String delete(@PathVariable Long id, RedirectAttributes attrs) {
        moduleRepository.deleteById(id);
        moduleSecurityInterceptor.clearCache(); 
        attrs.addFlashAttribute("successMessage", "Le module a été supprimé.");
        return "redirect:/admin/settings/modules";
    }
}
