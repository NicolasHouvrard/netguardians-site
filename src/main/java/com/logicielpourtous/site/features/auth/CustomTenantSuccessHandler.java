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
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
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
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();
    private final SecurityContextHolderStrategy holderStrategy = SecurityContextHolder.getContextHolderStrategy();

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

        boolean fromAngular = hasCookie(request, "oauth_origin", "angular");

        // Compte rechargé (rôles complets, appartenances). Le principal de la session fait foi s'il connaît
        // les appartenances (connexion par formulaire) ; un principal historique qui les ignore (OAuth2/OIDC)
        // cède la place au compte rechargé dès que celui-ci est en attente de choix de société.
        CustomUserDetails recharge = (CustomUserDetails) userDetailsService.loadUserByUsername(username);
        CustomUserDetails principal =
                authentication.getPrincipal() instanceof CustomUserDetails c && !c.appartenances().isEmpty()
                        ? c : null;
        CustomUserDetails userDetails = principal != null ? principal : recharge;

        // ── Plusieurs sociétés, aucune choisie : sélecteur d'abord ─────────────
        // Avant toute redirection par rôle, sans schéma de tenant ni jeton : la personne n'a encore aucun droit.
        if (userDetails.selectionRequise()) {
            if (!(authentication.getPrincipal() instanceof CustomUserDetails c && c.selectionRequise())) {
                remplacerAuthentification(request, response,
                        ChoixSocieteController.authentificationPour(authentication, userDetails));
            }
            request.getSession().removeAttribute("CURRENT_TENANT_ID");
            TenantContext.clear();
            if (fromAngular) {
                clearCookie(response, "oauth_origin");
            }
            if (user.isMustChangePassword()) {
                // L'obligation demeure : elle s'appliquera une fois la société choisie.
                request.getSession().setAttribute("MUST_CHANGE_PASSWORD", Boolean.TRUE);
            }
            response.sendRedirect(SelectionSocieteFilter.SELECTEUR);
            return;
        }

        // Schéma de la société du compte (société active s'il y en a une), et non plus celui de la fiche User.
        String schemaToSwitch = userDetails.getTenant().getSchemaName();
        TenantContext.setCurrentTenant(schemaToSwitch);
        request.getSession().setAttribute("CURRENT_TENANT_ID", schemaToSwitch);

        // ── Détection origine Angular ──────────────────────────────────────────
        if (fromAngular) {
            clearCookie(response, "oauth_origin");
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

        // userDetails : UserDetails complets (rechargés, ou principal à appartenances) pour hasEsnRole()
        // et hasPortalRole() (couvre les rôles custom par tenant, pas seulement les noms hardcodés).
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

    /** Remplace l'authentification de la requête et de la session (survit à la requête suivante). */
    private void remplacerAuthentification(HttpServletRequest request, HttpServletResponse response,
                                           Authentication nouvelle) {
        SecurityContext contexte = holderStrategy.createEmptyContext();
        contexte.setAuthentication(nouvelle);
        holderStrategy.setContext(contexte);
        securityContextRepository.saveContext(contexte, request, response);
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
