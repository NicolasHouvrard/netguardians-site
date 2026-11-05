package com.logicielpourtous.site.features.commun;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class TenantInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 1. On récupère le tenantId stocké en session (mis lors du login)
        String tenantId = (String) request.getSession().getAttribute("CURRENT_TENANT_ID");

        if (tenantId != null) {
            // 2. On l'injecte dans le contexte pour Hibernate
            TenantContext.setCurrentTenant(tenantId);
        } else {
            // 3. Par défaut, on peut mettre "public" ou bloquer l'accès
            TenantContext.setCurrentTenant("public");
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // TRÈS IMPORTANT : On nettoie le thread pour éviter les fuites de données entre utilisateurs
        TenantContext.clear();
    }
}