package com.logicielpourtous.site.config;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtTokenProvider {

    /** Revendication : identifiant de l'appartenance (societe active) choisie par la personne. */
    static final String REVENDICATION_APPARTENANCE = "app";

    /**
     * Revendication : jeton de selection de societe. Emis a la connexion d'une personne de plusieurs societes
     * qui n'a pas encore choisi ; il n'ouvre QUE {@code POST /api/auth/societe} (voir JwtAuthenticationFilter).
     */
    static final String REVENDICATION_SELECTION = "sel";

    /** Duree de vie d'un jeton de selection : le temps de choisir sa societe. */
    static final long DUREE_SELECTION_MS = 10 * 60 * 1000L;

    @Value("${app.jwt.secret:${APP_JWT_SECRET:dev-secret-key-must-be-at-least-32-bytes!!}}")
    private String secret;

    @Value("${app.jwt.expiration:86400000}")
    private long expirationMs;

    private SecretKey key() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generate(UserDetails user) {
        return Jwts.builder()
                .subject(user.getUsername())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(key())
                .compact();
    }

    /**
     * Jeton portant la societe active ({@code appartenanceId}) ; sans appartenance (nulle), identique a
     * {@link #generate(UserDetails)}. L'appartenance est revalidee en base a chaque requete.
     */
    public String generate(UserDetails user, Long appartenanceId) {
        if (appartenanceId == null) {
            return generate(user);
        }
        return Jwts.builder()
                .subject(user.getUsername())
                .claim(REVENDICATION_APPARTENANCE, appartenanceId)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(key())
                .compact();
    }

    /**
     * Jeton de selection de societe (sans societe, courte duree) : ne donne acces qu'au choix de la societe,
     * jamais a une autre route, meme si la personne n'a plus qu'une societe entre-temps.
     */
    public String generateSelection(UserDetails user) {
        return Jwts.builder()
                .subject(user.getUsername())
                .claim(REVENDICATION_SELECTION, Boolean.TRUE)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + Math.min(expirationMs, DUREE_SELECTION_MS)))
                .signWith(key())
                .compact();
    }

    /**
     * Jeton complet du compte : avec sa societe active s'il appartient a plusieurs societes, sinon le jeton
     * d'avant ({@link #generate(UserDetails)}). Refuse un compte en attente de choix : il n'a droit qu'a un
     * jeton de selection ({@link #generateSelection}).
     */
    public String jetonDuCompte(CustomUserDetails compte) {
        if (compte.selectionRequise()) {
            throw new IllegalStateException("Compte en attente de choix de societe : aucun jeton complet");
        }
        return compte.plusieursSocietes()
                ? generate(compte, compte.appartenanceActiveId())
                : generate(compte);
    }

    public String extractUsername(String token) {
        return claims(token).getSubject();
    }

    /**
     * Appartenance (societe active) portee par le jeton, nulle si absente.
     *
     * @throws JwtException si la revendication est presente mais n'est pas un nombre entier
     */
    public Long extractAppartenanceId(String token) {
        Object valeur = claims(token).get(REVENDICATION_APPARTENANCE);
        if (valeur == null) {
            return null;
        }
        if (valeur instanceof Integer || valeur instanceof Long) {
            return ((Number) valeur).longValue();
        }
        throw new MalformedJwtException("Revendication d'appartenance invalide");
    }

    /** Vrai si le jeton est un jeton de selection de societe ({@link #generateSelection}). */
    public boolean estJetonDeSelection(String token) {
        return Boolean.TRUE.equals(claims(token).get(REVENDICATION_SELECTION));
    }

    public boolean isValid(String token) {
        try {
            Jwts.parser().verifyWith(key()).build().parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private Claims claims(String token) {
        return Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload();
    }
}
