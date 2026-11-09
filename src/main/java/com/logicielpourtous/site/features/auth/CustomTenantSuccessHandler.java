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

    /**
     * Attribut de session : la connexion d'une personne en attente de choix de societe venait d'Angular
     * (cookie {@code oauth_origin}) ; le selecteur la renvoie a Angular une fois la societe choisie.
     */
    public static final String ORIGINE_ANGULAR_EN_ATTENTE = "ORIGINE_ANGULAR_EN_ATTENTE";

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
        // Indicateur d'une connexion precedente de la meme session : ne vaut que pour celle-ci.
        request.getSession().removeAttribute(ORIGINE_ANGULAR_EN_ATTENTE);

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
                // Retenu pour apres le choix : le selecteur renverra alors a Angular avec un jeton de la societe.
                request.getSession().setAttribute(ORIGINE_ANGULAR_EN_ATTENTE, Boolean.TRUE);
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

        if (fromAngular) {
            clearCookie(response, "oauth_origin");
        } else if (user.isMustChangePassword()) {
            request.getSession().setAttribute("MUST_CHANGE_PASSWORD", Boolean.TRUE);
        }
        response.sendRedirect(destinationApresConnexion(response, userDetails, user.isMustChangePassword(), fromAngular));
    }

    /**
     * Destination d'un compte qui a une societe (connexion, ou choix de societe par le selecteur) : retour a
     * Angular avec un jeton si la connexion venait d'Angular, sinon changement de mot de passe obligatoire,
     * sinon page d'accueil selon les droits ; pose le cookie {@code _intra_jwt} pour un admin ou un ESN.
     * Les jetons portent la societe active d'une personne de plusieurs societes.
     *
     * @return l'URL vers laquelle rediriger
     */
    public String destinationApresConnexion(HttpServletResponse response, CustomUserDetails userDetails,
                                            boolean doitChangerMotDePasse, boolean depuisAngular) {
        // ── Détection origine Angular ──────────────────────────────────────────
        if (depuisAngular) {
            return angularLoginRedirect + "?token=" + jwtTokenProvider.jetonDuCompte(userDetails);
        }

        // ── Redirection Thymeleaf classique ───────────────────────────────────
        if (doitChangerMotDePasse) {
            return "/change-password";
        }

        // userDetails : UserDetails complets (rechargés, ou principal à appartenances) pour hasEsnRole()
        // et hasPortalRole() (couvre les rôles custom par tenant, pas seulement les noms hardcodés).
        boolean isAdmin   = userDetails.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        boolean isEsnUser = !isAdmin && userDetails.hasEsnRole();

        // Pose un cookie JWT court (5 min) lisible par Angular pour le SSO auto-login
        if (isAdmin || isEsnUser) {
            String token = jwtTokenProvider.jetonDuCompte(userDetails);
            Cookie jwtCookie = new Cookie("_intra_jwt", token);
            jwtCookie.setPath("/");
            jwtCookie.setMaxAge(300);
            jwtCookie.setHttpOnly(false);
            response.addCookie(jwtCookie);
        }

        if (isAdmin)        return "/admin/console";
        else if (isEsnUser) return "/intra";
        else                return "/espace";
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
