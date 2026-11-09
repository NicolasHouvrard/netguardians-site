package com.logicielpourtous.site.features.api.angular;

import com.logicielpourtous.site.config.JwtTokenProvider;
import com.logicielpourtous.site.features.audit.AuditLogService;
import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.auth.CustomUserDetailsService;
import com.logicielpourtous.site.features.user.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthApiController {

    private final AuthenticationManager      authenticationManager;
    private final JwtTokenProvider           jwtTokenProvider;
    private final UserRepository             userRepository;
    private final GoogleAuthService          googleAuthService;
    private final AuditLogService            auditLogService;
    private final CustomUserDetailsService   userDetailsService;

    @Autowired(required = false)
    private ClientRegistrationRepository clientRegistrationRepository;

    public AuthApiController(AuthenticationManager authenticationManager,
                             JwtTokenProvider jwtTokenProvider,
                             UserRepository userRepository,
                             GoogleAuthService googleAuthService,
                             AuditLogService auditLogService,
                             CustomUserDetailsService userDetailsService) {
        this.authenticationManager = authenticationManager;
        this.jwtTokenProvider      = jwtTokenProvider;
        this.userRepository        = userRepository;
        this.googleAuthService     = googleAuthService;
        this.auditLogService       = auditLogService;
        this.userDetailsService    = userDetailsService;
    }

    public record LoginRequest(String email, String password) {}

    public record GoogleLoginRequest(String credential) {}

    /** Corps de {@code POST /api/auth/societe} : l'appartenance (societe) choisie. */
    public record ChoixSocieteRequest(Long appartenanceId) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req,
                                    HttpServletRequest request) {
        String ip = resolveIp(request);
        String ua = request.getHeader("User-Agent");
        try {
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.email(), req.password()));
            CustomUserDetails user = (CustomUserDetails) auth.getPrincipal();
            auditLogService.logLogin(user.getUserId(), user.getUsername(), ip, ua, true);
            return ResponseEntity.ok(reponseDeConnexion(user));
        } catch (BadCredentialsException e) {
            auditLogService.logLogin(null, req.email(), ip, ua, false);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Identifiants incorrects"));
        }
    }

    private String resolveIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        return request.getRemoteAddr();
    }

    @PostMapping("/google")
    public ResponseEntity<?> loginWithGoogle(@RequestBody GoogleLoginRequest req) {
        GoogleAuthService.GoogleUserInfo info = googleAuthService.verify(req.credential());
        if (info == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Token Google invalide ou email non vérifié"));
        }
        return userRepository.findByEmail(info.email())
                .filter(u -> u.isActive())
                .<ResponseEntity<?>>map(u -> {
                    // Compte charge avec ses appartenances (comme une connexion par mot de passe) : une personne
                    // de plusieurs societes doit d'abord choisir la sienne.
                    CustomUserDetails details;
                    try {
                        details = (CustomUserDetails) userDetailsService.charger(u.getEmail(), null);
                    } catch (UsernameNotFoundException e) {
                        return aucunCompte(info.email());
                    }
                    if (!details.isEnabled()) {
                        return aucunCompte(info.email());
                    }
                    return ResponseEntity.ok(reponseDeConnexion(details));
                })
                .orElseGet(() -> aucunCompte(info.email()));
    }

    private static ResponseEntity<?> aucunCompte(String email) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Aucun compte intranet associé à " + email));
    }

    /**
     * POST /api/auth/societe — choix (ou changement) de la societe active d'une personne de plusieurs societes.
     * Accepte le jeton de selection remis a la connexion, ou un jeton complet (changement de societe). Renvoie
     * un jeton portant la societe choisie ; 403 si l'appartenance n'est pas l'une de celles de la personne
     * connectee (jamais de recherche par identifiant seul), si le compte recharge n'y donne pas acces, ou si
     * la personne n'a qu'une societe.
     */
    @PostMapping("/societe")
    public ResponseEntity<?> choisirSociete(@RequestBody(required = false) ChoixSocieteRequest req,
                                            Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof CustomUserDetails compte)
                || req == null || req.appartenanceId() == null || !compte.plusieursSocietes()) {
            return interdit();
        }
        Long appartenanceId = req.appartenanceId();
        try {
            // Garde de propriete : l'identifiant doit etre l'une des appartenances du compte connecte.
            compte.avecAppartenanceActive(appartenanceId);
        } catch (IllegalArgumentException e) {
            return interdit();
        }
        // Compte recharge depuis la base avec la societe choisie (appartenance retiree, compte desactive...).
        CustomUserDetails choisi;
        try {
            choisi = (CustomUserDetails) userDetailsService.charger(compte.getUsername(), appartenanceId);
        } catch (UsernameNotFoundException e) {
            return interdit();
        }
        if (choisi.selectionRequise() || !choisi.plusieursSocietes() || choisi.getTenant() == null
                || !choisi.isEnabled() || !appartenanceId.equals(choisi.appartenanceActiveId())) {
            return interdit();
        }
        return ResponseEntity.ok(buildUserPayload(choisi, jwtTokenProvider.generate(choisi, appartenanceId)));
    }

    private static ResponseEntity<?> interdit() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Société non autorisée"));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request,
                                    HttpServletResponse response,
                                    Authentication auth) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            new SecurityContextLogoutHandler().logout(request, response, auth);
        }
        return ResponseEntity.ok(Map.of("message", "Déconnecté"));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof CustomUserDetails user)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // /me ne renvoie pas le token (il est déjà connu du client)
        return ResponseEntity.ok(buildUserPayload(user, null));
    }

    /**
     * GET /api/auth/providers — liste les providers OAuth2 actifs (public).
     * Retourne une liste vide si aucun n'est configuré.
     */
    /**
     * Retourne les providers OAuth2 actifs.
     * Si le repo est itérable (InMemoryClientRegistrationRepository), on le parcourt.
     * Sinon on sonde les IDs connus un par un.
     */
    @GetMapping("/providers")
    public List<Map<String, String>> providers() {
        if (clientRegistrationRepository == null) return List.of();
        List<Map<String, String>> result = new ArrayList<>();

        if (clientRegistrationRepository instanceof Iterable<?> iterable) {
            iterable.forEach(obj -> {
                if (obj instanceof ClientRegistration r) {
                    result.add(Map.of("id", r.getRegistrationId(), "name", r.getClientName()));
                }
            });
        } else {
            // Fallback : on sonde les providers connus
            for (String id : List.of("google", "microsoft", "github", "azure")) {
                ClientRegistration r = clientRegistrationRepository.findByRegistrationId(id);
                if (r != null) result.add(Map.of("id", r.getRegistrationId(), "name", r.getClientName()));
            }
        }
        return result;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Reponse d'une connexion reussie. Personne de plusieurs societes sans societe choisie : jeton de
     * selection (qui n'ouvre que {@code POST /api/auth/societe}), {@code selectionRequise} et la liste de ses
     * societes, sans roles ni tenant. Sinon : reponse d'avant, jeton complet.
     */
    private Map<String, Object> reponseDeConnexion(CustomUserDetails user) {
        if (!user.selectionRequise()) {
            return buildUserPayload(user, jwtTokenProvider.jetonDuCompte(user));
        }
        var map = new java.util.LinkedHashMap<String, Object>();
        map.put("token",            jwtTokenProvider.generateSelection(user));
        map.put("selectionRequise", true);
        map.put("societes",         user.appartenances().stream()
                .map(a -> {
                    var societe = new java.util.LinkedHashMap<String, Object>();
                    societe.put("id",      a.getId());
                    societe.put("libelle", a.getTenant() != null ? a.getTenant().getName() : "");
                    return societe;
                })
                .toList());
        map.put("email",            user.getUsername());
        map.put("fullName",         user.getFullName() != null ? user.getFullName() : "");
        map.put("userId",           user.getUserId());
        return map;
    }

    private Map<String, Object> buildUserPayload(CustomUserDetails user, String token) {
        List<String> roles = user.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).toList();
        var map = new java.util.LinkedHashMap<String, Object>();
        if (token != null) map.put("token", token);
        map.put("email",      user.getUsername());
        map.put("fullName",   user.getFullName() != null ? user.getFullName() : "");
        map.put("roles",      roles);
        map.put("userId",     user.getUserId());
        map.put("tenantId",   user.getTenant() != null ? user.getTenant().getId() : null);
        map.put("tenantName", user.getTenant() != null ? user.getTenant().getName() : "");
        return map;
    }
}
