package com.logicielpourtous.site.config;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Marqueur « compte unique » : pose en session a la connexion par le fournisseur authelia (et conserve au choix
 * de societe), il survit au remplacement de l'authentification OAuth2 par un jeton de formulaire. Ce handler le
 * lit dans la session encore valide (il s'execute avant SecurityContextLogoutHandler, qui l'invalide) et le
 * recopie dans un attribut de requete lu ensuite par {@link AutheliaLogoutSuccessHandler}.
 *
 * <p>Le marqueur ne donne aucun droit : il sert uniquement a choisir la page publique de fin de deconnexion.
 */
public class CompteUniqueLogoutHandler implements LogoutHandler {

    /** Attribut de session : la personne s'est connectee par le compte unique (valeur Boolean.TRUE). */
    public static final String COMPTE_UNIQUE = "COMPTE_UNIQUE";
    /** Attribut de requete pose pendant la deconnexion, lu par le LogoutSuccessHandler. */
    public static final String COMPTE_UNIQUE_REQUETE = "COMPTE_UNIQUE_REQUETE";

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        HttpSession session = request.getSession(false);
        if (session != null && Boolean.TRUE.equals(session.getAttribute(COMPTE_UNIQUE))) {
            request.setAttribute(COMPTE_UNIQUE_REQUETE, Boolean.TRUE);
        }
    }
}
