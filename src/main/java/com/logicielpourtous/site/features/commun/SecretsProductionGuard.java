package com.logicielpourtous.site.features.commun;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Garde-fou de demarrage : en profil {@code prod}, l'application refuse de demarrer si la cle de
 * chiffrement, son salt ou le secret JWT valent encore une valeur par defaut publique du depot.
 *
 * <p>Ces valeurs sont lisibles par tous (depot, fichiers .example) : une instance qui les
 * conserverait chiffrerait ses donnees et signerait ses jetons avec un secret connu de tout le
 * monde. Les vraies valeurs sont fournies par le paquet NAS (fichiers montes dans
 * /run/secrets-app et lus par configtree) ; si cette surcharge ne s'applique pas, on prefere un
 * arret net avec un message clair plutot qu'un demarrage silencieux avec un secret public.
 *
 * <p>Les messages n'affichent jamais les valeurs elles-memes.
 */
@Component
@Profile("prod")
public class SecretsProductionGuard {

    static final String SALT_PAR_DEFAUT = "deadbeefdeadbeef";

    public SecretsProductionGuard(
            @Value("${app.crypto.key}") String cleChiffrement,
            @Value("${app.crypto.salt}") String saltChiffrement,
            @Value("${app.jwt.secret:${APP_JWT_SECRET:}}") String secretJwt) {
        verifier(cleChiffrement, saltChiffrement, secretJwt);
    }

    static void verifier(String cleChiffrement, String saltChiffrement, String secretJwt) {
        if (estPlaceholder(cleChiffrement)) {
            throw refus("app.crypto.key");
        }
        if (saltChiffrement == null || saltChiffrement.isBlank() || SALT_PAR_DEFAUT.equalsIgnoreCase(saltChiffrement)) {
            throw refus("app.crypto.salt");
        }
        if (estPlaceholder(secretJwt)) {
            throw refus("app.jwt.secret");
        }
    }

    private static boolean estPlaceholder(String valeur) {
        if (valeur == null || valeur.isBlank()) {
            return true;
        }
        String v = valeur.toLowerCase(Locale.ROOT);
        return v.startsWith("changeme") || v.startsWith("dev-secret") || v.contains("votre-cle");
    }

    private static IllegalStateException refus(String propriete) {
        return new IllegalStateException(
                "Demarrage refuse en profil prod : " + propriete + " a encore sa valeur par defaut "
                        + "publique (ou est vide). Fournissez une vraie valeur secrete "
                        + "(fichier monte dans /run/secrets-app ou variable d'environnement).");
    }
}
