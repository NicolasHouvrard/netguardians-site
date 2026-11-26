package com.logicielpourtous.site.features.auth;

import java.io.IOException;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Echec de la connexion OpenID Connect : si c'est un compte inconnu ici ({@link AiguillageRequis}), renvoi vers
 * l'aiguillage ; sinon comportement habituel de Spring Security (retour a {@code /login?error}).
 */
public class AiguillageEchecHandler implements AuthenticationFailureHandler {

    private final SimpleUrlAuthenticationFailureHandler parDefaut = new SimpleUrlAuthenticationFailureHandler("/login?error");

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, jakarta.servlet.ServletException {
        if (exception instanceof AiguillageRequis) {
            response.sendRedirect(request.getContextPath() + AiguillageController.CHEMIN);
            return;
        }
        parDefaut.onAuthenticationFailure(request, response, exception);
    }
}
