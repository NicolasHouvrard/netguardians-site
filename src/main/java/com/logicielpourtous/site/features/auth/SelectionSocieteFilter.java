package com.logicielpourtous.site.features.auth;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Tant qu'une personne qui appartient a plusieurs societes n'a pas choisi la sienne
 * ({@link CustomUserDetails#selectionRequise()}), elle n'a acces a rien d'autre que le selecteur, la
 * deconnexion, les ressources statiques et la page d'erreur :
 * <ul>
 *   <li>une navigation de page (GET/HEAD acceptant du HTML, hors {@code /api/**} et {@code /ws/**}) est
 *       redirigee vers {@code /choisir-societe} (en-tete {@code HX-Redirect} pour une requete HTMX) ;</li>
 *   <li>tout le reste (API, poignee de main WebSocket, ecriture, requete JSON) est refuse en 403.</li>
 * </ul>
 * Place dans la chaine web juste avant l'autorisation, une fois l'authentification connue (session,
 * formulaire, remember-me). Ce n'est pas un composant Spring : il n'est enregistre que dans la chaine web.
 */
public class SelectionSocieteFilter extends OncePerRequestFilter {

    public static final String SELECTEUR = "/choisir-societe";

    private static final Set<String> METHODES_DE_NAVIGATION = Set.of("GET", "HEAD");

    private static final List<RequestMatcher> AUTORISES;
    private static final List<RequestMatcher> NON_NAVIGABLES;

    static {
        PathPatternRequestMatcher.Builder chemins = PathPatternRequestMatcher.withDefaults();
        AUTORISES = List.of(
                chemins.matcher(SELECTEUR),
                chemins.matcher("/logout"),
                chemins.matcher("/css/**"),
                chemins.matcher("/js/**"),
                chemins.matcher("/images/**"),
                chemins.matcher("/webjars/**"),
                chemins.matcher("/error"));
        NON_NAVIGABLES = List.of(
                chemins.matcher("/api/**"),
                chemins.matcher("/ws/**"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!enAttenteDeChoix() || correspond(AUTORISES, request)) {
            chain.doFilter(request, response);
            return;
        }
        if (!estUneNavigation(request)) {
            response.sendError(HttpStatus.FORBIDDEN.value());
            return;
        }
        if (request.getHeader("HX-Request") != null) {
            response.setHeader("HX-Redirect", request.getContextPath() + SELECTEUR);
            response.setStatus(HttpStatus.OK.value());
            return;
        }
        response.sendRedirect(request.getContextPath() + SELECTEUR);
    }

    private static boolean enAttenteDeChoix() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof CustomUserDetails compte && compte.selectionRequise();
    }

    private static boolean estUneNavigation(HttpServletRequest request) {
        if (!METHODES_DE_NAVIGATION.contains(request.getMethod()) || correspond(NON_NAVIGABLES, request)) {
            return false;
        }
        String accept = request.getHeader("Accept");
        return accept == null || accept.isBlank() || accept.contains("text/html") || accept.contains("*/*");
    }

    private static boolean correspond(List<RequestMatcher> matchers, HttpServletRequest request) {
        return matchers.stream().anyMatch(m -> m.matches(request));
    }
}
