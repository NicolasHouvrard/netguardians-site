package com.logicielpourtous.site.features.intra;

import com.logicielpourtous.site.features.module.AdminModule;
import com.logicielpourtous.site.features.module.AdminModuleRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.*;
import java.util.stream.Collectors;

@ControllerAdvice
public class IntraModulesAdvice {

    public static class ModuleSection {
        private final String category;
        private final List<AdminModule> modules;
        public ModuleSection(String c, List<AdminModule> m) { category = c; modules = m; }
        public String getCategory() { return category; }
        public List<AdminModule> getModules() { return modules; }
    }

    private final AdminModuleRepository moduleRepository;

    public IntraModulesAdvice(AdminModuleRepository moduleRepository) {
        this.moduleRepository = moduleRepository;
    }

    @ModelAttribute
    public void prepareIntraData(Authentication authentication, Model model) {
        List<ModuleSection> sections = Collections.emptyList();
        try {
            if (authentication != null && authentication.isAuthenticated()) {
                Set<String> roles = authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.toSet());

                boolean isAdmin = roles.contains("ROLE_ADMIN");

                Map<String, List<AdminModule>> byCategory =
                        moduleRepository.findAllByOrderByCategoryAscDisplayOrderAsc().stream()
                        .filter(AdminModule::isActive)
                        .filter(m -> "ESN".equals(m.getAudience()))
                        .filter(m -> isAdmin || roles.contains(m.getRoleRequired()))
                        .collect(Collectors.groupingBy(
                                m -> m.getCategory() != null ? m.getCategory() : "Autres",
                                LinkedHashMap::new,
                                Collectors.toList()));

                sections = byCategory.entrySet().stream()
                        .map(e -> new ModuleSection(e.getKey(), e.getValue()))
                        .collect(Collectors.toList());
            }
        } catch (Exception ignored) {}
        model.addAttribute("intraSections", sections);
    }
}
