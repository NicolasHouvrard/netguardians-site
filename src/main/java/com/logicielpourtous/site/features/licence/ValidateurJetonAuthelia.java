package com.logicielpourtous.site.features.licence;

import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * Valide le jeton d'identite (ID token) obtenu d'Authelia : signature (cles publiques lues chez l'emetteur
 * configure, mises en cache par Nimbus), emetteur, validite temporelle, destinataire, {@code email_verified}.
 * Seul RS256 est accepte (jamais {@code none}). Toute anomalie, y compris un emetteur injoignable, donne un
 * resultat vide ; le jeton et ses claims ne sont jamais journalises. Emetteur non configure : desactive, aucun
 * appel reseau.
 */
@Component
public class ValidateurJetonAuthelia {

    private static final Logger log = LoggerFactory.getLogger(ValidateurJetonAuthelia.class);

    private final JwtDecoder decodeur;

    @Autowired
    public ValidateurJetonAuthelia(@Value("${app.auth.authelia.issuer-uri:}") String emetteur) {
        if (emetteur == null || emetteur.isBlank()) {
            this.decodeur = null;
        } else {
            // La decouverte du jeu de cles est paresseuse (premier jeton) : demarrer sans reseau reste possible.
            this.decodeur = avecValidation(NimbusJwtDecoder.withIssuerLocation(emetteur.trim()).build(), emetteur.trim());
        }
    }

    /** Pour les tests : decodeur a cles locales (jeu de cles en memoire). */
    ValidateurJetonAuthelia(String emetteur, NimbusJwtDecoder decodeurLocal) {
        this.decodeur = avecValidation(decodeurLocal, emetteur);
    }

    private static JwtDecoder avecValidation(NimbusJwtDecoder d, String emetteur) {
        d.setJwtValidator(JwtValidators.createDefaultWithIssuer(emetteur));
        return d;
    }

    /** E-mail en minuscules si le jeton est entierement valide pour {@code clientIdAttendu}, sinon vide. */
    public Optional<String> valider(String idToken, String clientIdAttendu) {
        if (decodeur == null || idToken == null || idToken.isBlank()
                || clientIdAttendu == null || clientIdAttendu.isBlank()) {
            return Optional.empty();
        }
        try {
            Jwt jwt = decodeur.decode(idToken.trim());
            if (jwt.getAudience() == null || !jwt.getAudience().contains(clientIdAttendu)) {
                return Optional.empty();
            }
            if (!Boolean.TRUE.equals(jwt.getClaims().get("email_verified"))) {
                return Optional.empty();
            }
            String email = jwt.getClaimAsString("email");
            if (email == null || email.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(email.trim().toLowerCase(Locale.ROOT));
        } catch (RuntimeException e) {
            // Jamais le message de l'exception : il pourrait citer des fragments du jeton.
            log.debug("Jeton d'identite refuse ({}).", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
