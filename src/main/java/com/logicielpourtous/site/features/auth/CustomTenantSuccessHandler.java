package com.logicielpourtous.site.features.auth;

import com.logicielpourtous.site.config.JwtTokenProvider;
import com.logicielpourtous.site.features.audit.AuditLogService;
import com.logicielpourtous.site.features.commun.TenantContext;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@Component
public class CustomTenantSuccessHandler implements AuthenticationSuccessHandler {

    private final UserRepository          userRepository;
    private final CustomUserDetailsService userDetailsService;
    private final JwtTokenProvider        jwtTokenProvider;
    private final AuditLogService         auditLogService;

    /**
     * URL complète vers la page de login Angular.
     * Dev  : http://localhost:4200/login  (proxy dev server)
     * Prod : /app/login                  (même domaine, JAR embarqué)
     */
    @Value("${app.angular.login-redirect:http://localhost:4200/login}")
    private String angularLoginRedirect;

    public CustomTenantSuccessHandler(UserRepository userRepository,
                                      CustomUserDetailsService userDetailsService,
                                      JwtTokenProvider jwtTokenProvider,
                                      AuditLogService auditLogService) {
        this.userRepository    = userRepository;
        this.userDetailsService = userDetailsService;
        this.jwtTokenProvider  = jwtTokenProvider;
        this.auditLogService   = auditLogService;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {

        String username = authentication.getName();

        User user = userRepository.findByEmail(username)
                .orElseThrow(() -> new UsernameNotFoundException("Utilisateur non trouvé après authentification"));

        // Enregistre l'IP de connexion (supporte les proxies via X-Forwarded-For)
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) ip = request.getRemoteAddr();
        else ip = ip.split(",")[0].trim();
        user.setLastLoginIp(ip);
        userRepository.save(user);

        // Log de connexion réussie (avec GeoIP asynchrone)
        auditLogService.logLogin(user.getId(), user.getEmail(), ip,
                request.getHeader("User-Agent"), true);

        String schemaToSwitch = user.getTenant().getSchemaName();
        TenantContext.setCurrentTenant(schemaToSwitch);
        request.getSession().setAttribute("CURRENT_TENANT_ID", schemaToSwitch);

        // ── Détection origine Angular ──────────────────────────────────────────
        boolean fromAngular = hasCookie(request, "oauth_origin", "angular");
        if (fromAngular) {
            clearCookie(response, "oauth_origin");
            CustomUserDetails userDetails =
                    (CustomUserDetails) userDetailsService.loadUserByUsername(username);
            String token = jwtTokenProvider.generate(userDetails);
            response.sendRedirect(angularLoginRedirect + "?token=" + token);
            return;
        }

        // ── Redirection Thymeleaf classique ───────────────────────────────────
        if (user.isMustChangePassword()) {
            request.getSession().setAttribute("MUST_CHANGE_PASSWORD", Boolean.TRUE);
            response.sendRedirect("/change-password");
            return;
        }

        // Charge les UserDetails complets pour accéder à hasEsnRole() et hasPortalRole()
        // (couvre les rôles custom par tenant, pas seulement les noms hardcodés)
        CustomUserDetails userDetails =
                (CustomUserDetails) userDetailsService.loadUserByUsername(username);

        boolean isAdmin   = userDetails.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        boolean isEsnUser = !isAdmin && userDetails.hasEsnRole();

        // Pose un cookie JWT court (5 min) lisible par Angular pour le SSO auto-login
        if (isAdmin || isEsnUser) {
            String token = jwtTokenProvider.generate(userDetails);
            Cookie jwtCookie = new Cookie("_intra_jwt", token);
            jwtCookie.setPath("/");
            jwtCookie.setMaxAge(300);
            jwtCookie.setHttpOnly(false);
            response.addCookie(jwtCookie);
        }

        if (isAdmin)        response.sendRedirect("/admin/console");
        else if (isEsnUser) response.sendRedirect("/intra");
        else                response.sendRedirect("/espace");
    }

    private boolean hasCookie(HttpServletRequest request, String name, String value) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return false;
        return Arrays.stream(cookies)
                .anyMatch(c -> name.equals(c.getName()) && value.equals(c.getValue()));
    }

    private void clearCookie(HttpServletResponse response, String name) {
        Cookie c = new Cookie(name, "");
        c.setMaxAge(0);
        c.setPath("/");
        response.addCookie(c);
    }
}
