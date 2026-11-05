package com.logicielpourtous.site.config;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.logicielpourtous.site.features.module.AdminModule;
import com.logicielpourtous.site.features.module.AdminModuleRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class ModuleSecurityInterceptor implements HandlerInterceptor {

    private final AdminModuleRepository adminModuleRepository;

public ModuleSecurityInterceptor(AdminModuleRepository adminModuleRepository) {
    this.adminModuleRepository = adminModuleRepository;
}

    private Map<String, Optional<AdminModule>> moduleCache = new ConcurrentHashMap<>();

private Optional<AdminModule> findModule(String uri) {
    // D'abord chercher dans le cache exact
    if (moduleCache.containsKey(uri)) {
        return moduleCache.get(uri);
    }
    
    // Sinon chercher par préfixe
    Optional<AdminModule> result = adminModuleRepository.findAll().stream()
        .filter(m -> uri.startsWith(m.getUrl()))
        .findFirst();
    
    moduleCache.put(uri, result);
    return result;
}
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || auth.getPrincipal() == null
                || "anonymousUser".equals(auth.getPrincipal())) {
            response.sendRedirect("/login");
            return false;
        }

        // ROLE_ADMIN passe partout
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (isAdmin) return true;

        Optional<AdminModule> moduleOpt = findModule(uri);

        if (moduleOpt.isPresent()) {
            String requiredRole = moduleOpt.get().getRoleRequired();
            if (requiredRole != null && !requiredRole.isBlank()) {
                String[] required = requiredRole.split(",");
                boolean hasRole = auth.getAuthorities().stream()
                        .anyMatch(a -> {
                            for (String r : required) {
                                if (a.getAuthority().equals(r.trim())) return true;
                            }
                            return false;
                        });
                if (!hasRole) {
                    response.sendRedirect("/access-denied");
                    return false;
                }
            }
        }
        return true;
    }

    public void clearCache() {
    moduleCache.clear();
}
}
