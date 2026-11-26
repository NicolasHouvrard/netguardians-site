package com.logicielpourtous.site.config;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * Resolveur de requetes d'autorisation : PKCE (S256) pour le fournisseur « authelia » seulement, comportement
 * par defaut de Spring Security pour tous les autres.
 */
public class AutheliaAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    static final String BASE = "/oauth2/authorization";

    private final OAuth2AuthorizationRequestResolver parDefaut;
    private final OAuth2AuthorizationRequestResolver avecPkce;

    public AutheliaAuthorizationRequestResolver(ClientRegistrationRepository registrations) {
        this.parDefaut = new DefaultOAuth2AuthorizationRequestResolver(registrations, BASE);
        DefaultOAuth2AuthorizationRequestResolver pkce = new DefaultOAuth2AuthorizationRequestResolver(registrations, BASE);
        pkce.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        this.avecPkce = pkce;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        // Flux de connexion (oauth2Login) : la methode a un seul argument du resolveur par defaut extrait l'id de
        // /oauth2/authorization/{id} ET utilise l'action « login » dans l'adresse de retour
        // ({baseUrl}/login/oauth2/code/{id}). La methode a deux arguments utilise « authorize » (oauth2Client).
        String prefixe = request.getContextPath() + BASE + "/";
        String uri = request.getRequestURI();
        boolean authelia = uri != null && (prefixe + "authelia").equals(uri);
        return (authelia ? avecPkce : parDefaut).resolve(request);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return ("authelia".equals(clientRegistrationId) ? avecPkce : parDefaut).resolve(request, clientRegistrationId);
    }
}
