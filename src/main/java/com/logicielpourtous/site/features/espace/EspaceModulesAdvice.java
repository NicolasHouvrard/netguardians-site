package com.logicielpourtous.site.features.espace;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.module.AdminModule;
import com.logicielpourtous.site.features.module.AdminModuleRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.*;
import java.util.stream.Collectors;

@ControllerAdvice
public class EspaceModulesAdvice {

    public static class ModuleSection {
        private final String category;
        private final List<AdminModule> modules;
        public ModuleSection(String c, List<AdminModule> m) { category = c; modules = m; }
        public String getCategory() { return category; }
        public List<AdminModule> getModules() { return modules; }
    }

    private final AdminModuleRepository moduleRepository;
    private final CustomerRepository    customerRepository;

    public EspaceModulesAdvice(AdminModuleRepository moduleRepository,
                               CustomerRepository customerRepository) {
        this.moduleRepository   = moduleRepository;
        this.customerRepository = customerRepository;
    }

    @ModelAttribute
    public void addAdminContext(@AuthenticationPrincipal CustomUserDetails userDetails,
                                HttpSession session, Model model) {
        if (userDetails == null) return;
        boolean isInternal = userDetails.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")
                            || a.getAuthority().equals("ROLE_USER"));
        if (isInternal) {
            model.addAttribute("allClients", customerRepository.findAll());
            model.addAttribute("portalViewClientId", session.getAttribute("portalViewClientId"));
        }
    }

    @ExceptionHandler(PortalNotLinkedException.class)
    public String handleNotLinked(Authentication auth, Model model) {
        boolean isInternalUser = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")
                            || a.getAuthority().equals("ROLE_USER"));
        if (isInternalUser) return "redirect:/espace";
        model.addAttribute("customer", null);
        return "espace/not-linked";
    }

    @ModelAttribute
    public void prepareEspaceData(Authentication authentication, Model model) {
        List<ModuleSection> sections = Collections.emptyList();

        try {
            if (authentication != null && authentication.isAuthenticated()) {
                Set<String> roles = authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.toSet());

                boolean isAdmin = roles.contains("ROLE_ADMIN");
                boolean isEsnUser = isAdmin || roles.stream().anyMatch(r ->
                        r.equals("ROLE_USER") || r.equals("ROLE_TECHNICIEN") ||
                        r.equals("ROLE_COMMERCIAL") || r.equals("ROLE_MARKETING") ||
                        r.equals("ROLE_COMPTABLE"));
                boolean isPortalUser    = roles.stream().anyMatch(r ->
                        r.equals("ROLE_CLIENT") || r.equals("ROLE_GESTIONNAIRE"));
                boolean isGestionnaire = roles.contains("ROLE_GESTIONNAIRE");

                Map<String, List<AdminModule>> byCategory =
                        moduleRepository.findAllByOrderByCategoryAscDisplayOrderAsc().stream()
                        .filter(AdminModule::isActive)
                        .filter(m -> {
                            String aud = m.getAudience();
                            if ("ESN".equals(aud) && !isEsnUser) return false;
                            if ("CLIENT".equals(aud) && !isPortalUser) return false;
                            if (isAdmin) return true;
                            // Le gestionnaire est aussi client : il voit les modules ROLE_CLIENT
                            if (isGestionnaire && "ROLE_CLIENT".equals(m.getRoleRequired())) return true;
                            return roles.contains(m.getRoleRequired());
                        })
                        .collect(Collectors.groupingBy(
                                m -> m.getCategory() != null ? m.getCategory() : "Autres",
                                LinkedHashMap::new,
                                Collectors.toList()));

                sections = byCategory.entrySet().stream()
                        .map(e -> new ModuleSection(e.getKey(), e.getValue()))
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            // sections reste Collections.emptyList()
        }

        List<ModuleSection> safe = sections != null ? sections : List.of();
        List<ModuleSection> esnSections = safe.stream()
                .filter(s -> s.getModules().stream().anyMatch(m -> !"CLIENT".equals(m.getAudience())))
                .collect(Collectors.toList());
        model.addAttribute("espaceSections", safe);
        model.addAttribute("intraSections",  esnSections);
    }

    @ModelAttribute("currentUri")
    public String currentUri(HttpServletRequest request) {
        return request.getRequestURI();
    }
}
