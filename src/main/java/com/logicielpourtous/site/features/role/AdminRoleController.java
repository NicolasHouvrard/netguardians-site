package com.logicielpourtous.site.features.role;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import  com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.user.UserRepository;
import com.logicielpourtous.site.features.module.AdminModuleRepository;

@Controller
@RequestMapping("/admin/settings/roles")
public class AdminRoleController {
   
    @Autowired private RoleRepository roleRepository;

    @Autowired private UserRepository userRepository;
    @Autowired private AdminModuleRepository adminModuleRepository;

    

    // Renvoie le fragment HTMX pour la liste des rôles
    @GetMapping
    public String list(Model model) {
        model.addAttribute("roles", roleRepository.findAll());
        return "feature/role/list";
    }

    @PostMapping("/add")
    @PreAuthorize("isFullyAuthenticated()")
    public String addRole(@RequestParam String roleName, Model model) {
        if(!roleName.isEmpty()) {
            roleRepository.save(new Role(roleName.toUpperCase()));
        }
        return "feature/role/list"; // HTMX rafraîchit la liste
    }
    
     @PostMapping("/save")
    public String save(@RequestParam String roleName, RedirectAttributes attrs) {
        // Normalisation : ROLE_ + NOM en majuscules
        String formattedName = roleName.toUpperCase();
        if (!formattedName.startsWith("ROLE_")) {
            formattedName = "ROLE_" + formattedName;
        }

        if (roleRepository.findByName(formattedName).isPresent()) {
            attrs.addFlashAttribute("errorMessage", "Ce rôle existe déjà !");
        } else {
            roleRepository.save(new Role(formattedName));
            attrs.addFlashAttribute("successMessage", "Rôle ajouté avec succès.");
        }
        return "redirect:/admin/settings/roles";
    }   
    
    @GetMapping("/delete/{id}")
public String delete(@PathVariable Long id, RedirectAttributes attrs) {
    Role role = roleRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Rôle introuvable"));

    // 1. Vérifier si des utilisateurs possèdent ce rôle
    // (Supposant que tu as une méthode countByRoles dans ton UserRepository)
    long userCount = userRepository.countByRolesContaining(role);
    
    // 2. Vérifier si des modules utilisent ce rôle
    long moduleCount = adminModuleRepository.countByRoleRequired(role.getName());

    if (userCount > 0 || moduleCount > 0) {
        attrs.addFlashAttribute("errorMessage", 
            String.format("Suppression impossible : ce rôle est utilisé par %d utilisateur(s) et %d module(s).", 
            userCount, moduleCount));
        return "redirect:/admin/settings/roles";
    }

    roleRepository.delete(role);
    attrs.addFlashAttribute("successMessage", "Le rôle a été supprimé en toute sécurité.");
    return "redirect:/admin/settings/roles";
}
}
