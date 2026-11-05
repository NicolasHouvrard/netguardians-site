package com.logicielpourtous.site.features.role;

import com.logicielpourtous.site.features.module.AdminModuleRepository;
import com.logicielpourtous.site.features.user.UserRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/intra/roles")
public class IntraRoleController {

    private final RoleRepository        roleRepository;
    private final UserRepository        userRepository;
    private final AdminModuleRepository moduleRepository;

    public IntraRoleController(RoleRepository roleRepository,
                               UserRepository userRepository,
                               AdminModuleRepository moduleRepository) {
        this.roleRepository  = roleRepository;
        this.userRepository  = userRepository;
        this.moduleRepository = moduleRepository;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("esnRoles",    roleRepository.findByTypeOrderByLabel("ESN"));
        model.addAttribute("clientRoles", roleRepository.findByTypeOrderByLabel("CLIENT"));
        return "intra/roles/list";
    }

    @PostMapping
    public String create(@RequestParam String name,
                         @RequestParam String label,
                         @RequestParam String type,
                         RedirectAttributes ra) {
        String normalized = name.toUpperCase().trim();
        if (!normalized.startsWith("ROLE_")) normalized = "ROLE_" + normalized;

        if (roleRepository.findByName(normalized).isPresent()) {
            ra.addFlashAttribute("errorMessage", "Le rôle « " + normalized + " » existe déjà.");
            return "redirect:/intra/roles";
        }
        roleRepository.save(new Role(normalized, label.trim(), type));
        ra.addFlashAttribute("successMessage", "Rôle « " + label + " » créé.");
        return "redirect:/intra/roles";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        roleRepository.findById(id).ifPresent(role -> {
            long users   = userRepository.countByRolesContaining(role);
            long modules = moduleRepository.countByRoleRequired(role.getName());
            if (users > 0 || modules > 0) {
                ra.addFlashAttribute("errorMessage",
                        "Impossible : rôle utilisé par " + users + " utilisateur(s) et "
                        + modules + " module(s).");
            } else {
                roleRepository.delete(role);
                ra.addFlashAttribute("successMessage", "Rôle supprimé.");
            }
        });
        return "redirect:/intra/roles";
    }
}
