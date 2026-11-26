package com.logicielpourtous.site.features.auth;

import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

/**
 * Identite Authelia valide mais sans compte local sur ce serveur secondaire : la connexion est ecartee ici et
 * l'utilisateur est renvoye vers l'aiguillage (le Maitre dit ou il habite). Le message est le refus generique habituel.
 */
public class AiguillageRequis extends OAuth2AuthenticationException {

    /** Attribut de session portant le jeton d'identite (secret) ; retire des sa premiere lecture. */
    public static final String ATTRIBUT_JETON = "AIGUILLAGE_ID_TOKEN";

    /** Attribut de session portant l'instant (millisecondes) ou le jeton a ete conserve. */
    public static final String ATTRIBUT_JETON_INSTANT = "AIGUILLAGE_ID_TOKEN_INSTANT";

    /** Duree de vie maximale du jeton conserve en session : au-dela, il est refuse. */
    public static final long DUREE_MAX_MS = 5 * 60_000L;

    /** Attribut de session portant la liste renvoyee par le Maitre, contre laquelle le choix est valide. */
    public static final String ATTRIBUT_LISTE = "AIGUILLAGE_DESTINATIONS";

    /** Retire de la session tout ce que l'aiguillage y avait conserve (jeton, horodatage, liste). */
    public static void retirer(jakarta.servlet.http.HttpSession session) {
        if (session != null) {
            session.removeAttribute(ATTRIBUT_JETON);
            session.removeAttribute(ATTRIBUT_JETON_INSTANT);
            session.removeAttribute(ATTRIBUT_LISTE);
        }
    }

    public AiguillageRequis() {
        super(new OAuth2Error("connexion_refusee"), OidcSocialLoginUserService.MESSAGE_REFUS);
    }
}
