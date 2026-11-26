package com.logicielpourtous.site.config;

import java.io.IOException;

import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Clic sur « Se connecter avec le compte unique » alors qu'Authelia est injoignable : retour a la page de
 * connexion avec un message clair, au lieu d'une erreur 500.
 */
public class AutheliaIndisponibleFilter extends OncePerRequestFilter {

    private final ClientRegistrationRepository registrations;

    public AutheliaIndisponibleFilter(ClientRegistrationRepository registrations) {
        this.registrations = registrations;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(request.getContextPath() + AutheliaAuthorizationRequestResolver.BASE + "/authelia")
                .equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (registrations.findByRegistrationId("authelia") == null) {
            response.sendRedirect(request.getContextPath() + "/login?error=authelia");
            return;
        }
        chain.doFilter(request, response);
    }
}
