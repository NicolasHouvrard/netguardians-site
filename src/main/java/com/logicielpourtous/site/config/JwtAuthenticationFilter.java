package com.logicielpourtous.site.config;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.auth.CustomUserDetailsService;
import com.logicielpourtous.site.features.commun.TenantContext;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Authentification par jeton JWT (chaines API). Le compte est recharge en base a chaque requete avec la
 * societe active portee par le jeton ({@code charger(email, appartenanceId)}) : une appartenance retiree ou
 * un compte desactive cessent aussitot d'ouvrir des droits.
 * <p>
 * Refus par defaut (aucune authentification, la chaine renvoie alors son refus habituel) :
 * <ul>
 *   <li>compte inconnu, desactive, sans tenant, ou en attente de choix de societe ;</li>
 *   <li>jeton portant une appartenance qui n'est pas (ou plus) l'une de celles de la personne ;</li>
 *   <li>jeton de selection de societe, sauf sur {@code POST /api/auth/societe} et seulement si la personne
 *       est effectivement en attente de choix.</li>
 * </ul>
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** Seule route ouverte a un jeton de selection de societe (personne en attente de choix). */
    public static final String CHOIX_SOCIETE = "/api/auth/societe";

    private static final RequestMatcher CHOIX_SOCIETE_POST =
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, CHOIX_SOCIETE);

    private final JwtTokenProvider jwtTokenProvider;
    private final CustomUserDetailsService userDetailsService;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider,
                                   CustomUserDetailsService userDetailsService) {
        this.jwtTokenProvider   = jwtTokenProvider;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            if (jwtTokenProvider.isValid(token)) {
                CustomUserDetails compte = compteAutorise(token, request);
                if (compte != null) {
                    UsernamePasswordAuthenticationToken auth =
                            new UsernamePasswordAuthenticationToken(compte, null, compte.getAuthorities());
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth);

                    // Positionner le tenant pour les requêtes stateless (API JWT) — jamais pour un compte en
                    // attente de choix (il n'a pas de tenant).
                    if (!compte.selectionRequise() && compte.getTenant() != null) {
                        TenantContext.setCurrentTenant(compte.getTenant().getSchemaName());
                    }
                }
            }
        }
        chain.doFilter(request, response);
    }

    /** Compte a authentifier pour ce jeton et cette requete, ou nul (refus). */
    private CustomUserDetails compteAutorise(String token, HttpServletRequest request) {
        try {
            String email = jwtTokenProvider.extractUsername(token);
            if (jwtTokenProvider.estJetonDeSelection(token)) {
                // Jeton de selection : uniquement le choix de societe, et seulement pour une personne
                // toujours en attente de choix (sinon il ne doit rien ouvrir du tout).
                if (!CHOIX_SOCIETE_POST.matches(request)) {
                    return null;
                }
                CustomUserDetails compte = charger(email, null);
                return compte != null && compte.isEnabled() && compte.selectionRequise() ? compte : null;
            }

            Long appartenanceId = jwtTokenProvider.extractAppartenanceId(token);
            CustomUserDetails compte = charger(email, appartenanceId);
            if (compte == null || !compte.isEnabled() || compte.selectionRequise() || compte.getTenant() == null) {
                return null;
            }
            // Le jeton designe une societe precise : elle doit etre (encore) l'une de celles de la personne.
            // Sinon le jeton n'ouvre pas une autre societe a sa place (appartenance retiree, revendication forgee).
            if (appartenanceId != null && !appartenanceId.equals(compte.appartenanceActiveId())) {
                return null;
            }
            return compte;
        } catch (JwtException | IllegalArgumentException | UsernameNotFoundException e) {
            return null;
        }
    }

    private CustomUserDetails charger(String email, Long appartenanceId) {
        UserDetails user = userDetailsService.charger(email, appartenanceId);
        return user instanceof CustomUserDetails cu ? cu : null;
    }
}
