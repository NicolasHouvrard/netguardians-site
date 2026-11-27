package com.logicielpourtous.site.config;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.authentication.logout.SimpleUrlLogoutSuccessHandler;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Fin de deconnexion. Pour un utilisateur connecte par le fournisseur « authelia » (et si l'adresse du portail est
 * configuree en https), le site ferme sa session puis affiche sa page {@value #PAGE} qui propose de fermer aussi la
 * session du compte unique (Authelia 4.39 n'a pas de deconnexion OpenID Connect ; elle arrive en 4.40). Pour tous les
 * autres cas (compte local, Google, Microsoft, portail non configure ou pas en https) : comportement habituel
 * (retour a /login?logout).
 *
 * <p>Aucun appel reseau ; une requete HTMX recoit HX-Redirect (200) au lieu d'un 302.
 */
public class AutheliaLogoutSuccessHandler implements LogoutSuccessHandler {

    /** Page du site affichee apres la deconnexion d'un compte unique. */
    public static final String PAGE = "/deconnecte?sso=1";
    private static final Logger LOG = LoggerFactory.getLogger(AutheliaLogoutSuccessHandler.class);
    private static final String FOURNISSEUR = "authelia";

    private final SimpleUrlLogoutSuccessHandler parDefaut = new SimpleUrlLogoutSuccessHandler();
    private final boolean portailUtilisable;

    public AutheliaLogoutSuccessHandler(String issuerUri) {
        parDefaut.setDefaultTargetUrl("/login?logout");
        this.portailUtilisable = urlDeconnexionPortail(issuerUri) != null;
    }

    /**
     * Adresse de la page de deconnexion du portail ({@code <issuer-uri sans slash final>/logout}), construite a partir de
     * la seule configuration ; null si elle est absente ou n'est pas en https.
     */
    public static String urlDeconnexionPortail(String issuerUri) {
        String base = issuerUri == null ? "" : issuerUri.trim();
        if (base.isEmpty()) {
            return null;
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (!base.regionMatches(true, 0, "https://", 0, 8) || base.length() == 8) {
            LOG.warn("Deconnexion unique ignoree : l'adresse du portail Authelia doit etre en https.");
            return null;
        }
        return base + "/logout";
    }

    @Override
    public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response,
                                Authentication authentication) throws IOException, ServletException {
        if (portailUtilisable && authentication instanceof OAuth2AuthenticationToken jeton
                && FOURNISSEUR.equals(jeton.getAuthorizedClientRegistrationId())) {
            String cible = request.getContextPath() + PAGE;
            if ("true".equals(request.getHeader("HX-Request"))) {
                response.setHeader("HX-Redirect", cible);
                response.setStatus(HttpServletResponse.SC_OK);
            } else {
                response.sendRedirect(cible);
            }
            return;
        }
        parDefaut.onLogoutSuccess(request, response, authentication);
    }
}
