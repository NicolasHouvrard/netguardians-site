package com.logicielpourtous.site.features.sync;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authentifie les routes {@code /api/sync/**} par l'en-tete {@value #HEADER} : l'empreinte de la
 * cle presentee doit correspondre a un {@link SecondaryServer} au statut {@code ACTIF}. En cas de
 * succes, le principal est l'id ({@code Long}) du serveur secondaire, avec l'autorite
 * {@value #AUTHORITY}. Sinon le filtre n'authentifie rien : la chaine de securite repond 401.
 *
 * <p>Volontairement PAS un {@code @Component} : Spring Boot enregistrerait sinon ce filtre comme
 * filtre servlet global, actif sur toutes les routes. Il est instancie uniquement dans la chaine
 * {@code SecurityConfig.syncApiFilterChain}.
 */
public class SyncApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Sync-Api-Key";
    public static final String ROLE = "SYNC_SECONDARY";
    public static final String AUTHORITY = "ROLE_" + ROLE;

    private final SecondaryServerRepository secondaryServerRepository;

    public SyncApiKeyAuthFilter(SecondaryServerRepository secondaryServerRepository) {
        this.secondaryServerRepository = secondaryServerRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String apiKey = request.getHeader(HEADER);
        if (apiKey != null && !apiKey.isBlank()) {
            secondaryServerRepository.findByApiKeyHash(SyncApiKeys.hash(apiKey.trim()))
                    .filter(s -> s.getStatut() == SecondaryServer.SecondaryServerStatut.ACTIF)
                    .ifPresent(s -> SecurityContextHolder.getContext().setAuthentication(
                            UsernamePasswordAuthenticationToken.authenticated(
                                    s.getId(), null, List.of(new SimpleGrantedAuthority(AUTHORITY)))));
        }
        chain.doFilter(request, response);
    }
}
