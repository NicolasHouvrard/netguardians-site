package com.logicielpourtous.site.features.api.mobile;

import com.logicielpourtous.site.config.JwtTokenProvider;
import com.logicielpourtous.site.features.api.mobile.dto.LoginRequest;
import com.logicielpourtous.site.features.api.mobile.dto.LoginResponse;
import com.logicielpourtous.site.features.auth.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/mobile/auth")
@Tag(name = "Authentification", description = "Obtenir un token JWT")
public class MobileAuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;

    public MobileAuthController(AuthenticationManager authenticationManager,
                                JwtTokenProvider jwtTokenProvider) {
        this.authenticationManager = authenticationManager;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Operation(summary = "Connexion", description = "Retourne un token JWT valable 24h. Passez-le ensuite dans le header Authorization: Bearer {token}.")
    @ApiResponse(responseCode = "200", description = "Authentification réussie")
    @ApiResponse(responseCode = "401", description = "Identifiants incorrects")
    @SecurityRequirements
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest req) {
        try {
            Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(req.email(), req.password())
            );
            CustomUserDetails user = (CustomUserDetails) auth.getPrincipal();
            // Personne de plusieurs societes : l'API mobile ne propose pas de choix de societe, aucun jeton
            // n'est remis (refus). Elle peut obtenir un jeton de sa societe par POST /api/auth/societe.
            if (user.selectionRequise()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            String token = jwtTokenProvider.jetonDuCompte(user);
            List<String> roles = user.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
            return ResponseEntity.ok(new LoginResponse(token, user.getUsername(), user.getFullName(), roles));
        } catch (BadCredentialsException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }
}
