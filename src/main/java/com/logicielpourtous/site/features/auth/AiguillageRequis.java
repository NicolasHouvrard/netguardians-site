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

    /** Attribut de session portant la liste renvoyee par le Maitre, contre laquelle le choix est valide. */
    public static final String ATTRIBUT_LISTE = "AIGUILLAGE_DESTINATIONS";

    public AiguillageRequis() {
        super(new OAuth2Error("connexion_refusee"), OidcSocialLoginUserService.MESSAGE_REFUS);
    }
}
