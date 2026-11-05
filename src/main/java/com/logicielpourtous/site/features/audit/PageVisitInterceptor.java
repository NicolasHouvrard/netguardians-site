package com.logicielpourtous.site.features.audit;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;
import java.util.regex.Pattern;

@Component
public class PageVisitInterceptor implements HandlerInterceptor {

    private static final Set<String> TRACKED_PREFIXES = Set.of("/intra/", "/espace/", "/admin/");
    private static final Pattern     STATIC_EXT       =
            Pattern.compile("\\.(js|css|png|jpg|jpeg|gif|svg|ico|woff|woff2|ttf|map|webp)$");

    private final AuditLogService auditLogService;

    public PageVisitInterceptor(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                              HttpServletResponse response,
                              Object handler) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) return true;

        String uri = request.getRequestURI();
        if (STATIC_EXT.matcher(uri).find())                                   return true;
        if (TRACKED_PREFIXES.stream().noneMatch(uri::startsWith))             return true;
        if (uri.contains("/api/"))                                            return true;

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return true;
        if (!(auth.getPrincipal() instanceof CustomUserDetails user)) return true;

        auditLogService.logPageVisit(
                user.getUserId(),
                user.getUsername(),
                uri,
                resolveIp(request),
                request.getHeader("User-Agent")
        );
        return true;
    }

    private String resolveIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
