package com.logicielpourtous.site.features.api.angular;

import com.logicielpourtous.site.config.JwtTokenProvider;
import com.logicielpourtous.site.features.auth.CustomUserDetails;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pont SSO : permet à l'app Angular de récupérer un JWT depuis
 * une session Thymeleaf existante (JSESSIONID cookie).
 *
 * Ce controller est intentionnellement HORS du chemin /api/**
 * pour ne pas être capturé par la chaîne STATELESS.
 * Il est géré par la chaîne session-based (Order 3).
 */
@RestController
@RequestMapping("/auth")
public class SessionBridgeController {

    private final JwtTokenProvider jwtTokenProvider;

    public SessionBridgeController(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * GET /auth/session-token
     *
     * Si une session Spring Security est active (cookie JSESSIONID valide),
     * retourne un JWT Angular. Sinon 401.
     * Appelé par Angular au démarrage pour éviter une double connexion.
     */
    @GetMapping("/session-token")
    public ResponseEntity<?> sessionToken(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()
                || !(auth.getPrincipal() instanceof CustomUserDetails user)) {
            return ResponseEntity.status(401).build();
        }
        // Personne de plusieurs societes sans societe choisie : aucun jeton (elle choisit d'abord sur le web).
        if (user.selectionRequise() || user.getTenant() == null || !user.isEnabled()) {
            return ResponseEntity.status(401).build();
        }

        // Jeton portant la societe active (personne de plusieurs societes), sinon le jeton d'avant.
        String token = jwtTokenProvider.jetonDuCompte(user);
        List<String> roles = user.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token",      token);
        body.put("email",      user.getUsername());
        body.put("fullName",   user.getFullName() != null ? user.getFullName() : "");
        body.put("roles",      roles);
        body.put("userId",     user.getUserId());
        body.put("tenantId",   user.getTenant() != null ? user.getTenant().getId()   : null);
        body.put("tenantName", user.getTenant() != null ? user.getTenant().getName() : "");

        return ResponseEntity.ok(body);
    }
}
