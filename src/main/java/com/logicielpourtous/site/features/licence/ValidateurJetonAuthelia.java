package com.logicielpourtous.site.features.licence;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Valide le jeton d'identite (ID token) obtenu d'Authelia : signature RS256 uniquement (cles publiques lues chez
 * l'emetteur configure, mises en cache par Nimbus ; jamais {@code none} ni HMAC), emetteur, validite temporelle,
 * destinataire ({@code aud} contient le client attendu, et {@code azp} le designe si {@code aud} est multiple),
 * {@code email_verified} strictement vrai.
 *
 * <p>Aucun appel reseau a la construction : le decodeur (decouverte de l'emetteur) est cree au PREMIER jeton, un
 * seul essai a la fois (les autres requetes ne sont pas bloquees et recoivent « refuse »). Un emetteur injoignable
 * ou une erreur d'infrastructure donne « refuse », avec un avertissement sans jeton ni secret, et n'est pas
 * retente avant 30 s. Delais reseau courts. Toute anomalie donne un resultat vide. Emetteur non configure :
 * desactive, aucun appel reseau.
 */
@Component
public class ValidateurJetonAuthelia {

    private static final Logger log = LoggerFactory.getLogger(ValidateurJetonAuthelia.class);

    static final long PAUSE_ENTRE_ESSAIS_NANOS = Duration.ofSeconds(30).toNanos();
    private static final Duration DELAI_RESEAU = Duration.ofSeconds(3);

    private final Supplier<JwtDecoder> fabrique;
    private final LongSupplier horloge;
    private final boolean actif;
    private final AtomicBoolean essaiEnCours = new AtomicBoolean();
    private volatile JwtDecoder decodeur;
    /** Prochain instant (nanoTime) autorise pour un nouvel essai reseau ; 0 = pas de pause. */
    private volatile long pauseJusqua;
    private volatile boolean enPause;

    @Autowired
    public ValidateurJetonAuthelia(@Value("${app.auth.authelia.issuer-uri:}") String emetteur) {
        this(emetteur == null ? "" : emetteur.trim(), fabriqueDeProduction(emetteur == null ? "" : emetteur.trim()),
                System::nanoTime);
    }

    /** Pour les tests : decodeur a cles locales (jeu de cles en memoire). */
    ValidateurJetonAuthelia(String emetteur, NimbusJwtDecoder decodeurLocal) {
        this(emetteur, () -> avecValidation(decodeurLocal, emetteur), System::nanoTime);
    }

    /** Pour les tests : fabrique de decodeur et horloge injectees. */
    ValidateurJetonAuthelia(String emetteur, Supplier<JwtDecoder> fabrique, LongSupplier horloge) {
        this.actif = emetteur != null && !emetteur.isBlank();
        this.fabrique = fabrique;
        this.horloge = horloge;
    }

    private static Supplier<JwtDecoder> fabriqueDeProduction(String emetteur) {
        return () -> {
            SimpleClientHttpRequestFactory usine = new SimpleClientHttpRequestFactory();
            usine.setConnectTimeout(DELAI_RESEAU);
            usine.setReadTimeout(DELAI_RESEAU);
            NimbusJwtDecoder d = NimbusJwtDecoder.withIssuerLocation(emetteur)
                    .jwsAlgorithm(SignatureAlgorithm.RS256)
                    .restOperations(new RestTemplate(usine))
                    .build();
            return avecValidation(d, emetteur);
        };
    }

    private static JwtDecoder avecValidation(NimbusJwtDecoder d, String emetteur) {
        d.setJwtValidator(JwtValidators.createDefaultWithIssuer(emetteur));
        return d;
    }

    /** E-mail en minuscules si le jeton est entierement valide pour {@code clientIdAttendu}, sinon vide. */
    public Optional<String> valider(String idToken, String clientIdAttendu) {
        if (!actif || idToken == null || idToken.isBlank() || clientIdAttendu == null || clientIdAttendu.isBlank()) {
            return Optional.empty();
        }
        JwtDecoder d = decodeurPret();
        if (d == null) {
            return Optional.empty();
        }
        try {
            return controler(d.decode(idToken.trim()), clientIdAttendu);
        } catch (BadJwtException e) {
            // Jeton refuse (signature, emetteur, dates, algorithme...) : jamais le message, il pourrait citer le jeton.
            log.debug("Jeton d'identite refuse ({}).", e.getClass().getSimpleName());
            return Optional.empty();
        } catch (RuntimeException e) {
            // Probablement l'emetteur ou ses cles injoignables : pause avant de retenter.
            mettreEnPause("Cles de l'emetteur Authelia indisponibles", e);
            return Optional.empty();
        }
    }

    private static Optional<String> controler(Jwt jwt, String clientIdAttendu) {
        List<String> aud = jwt.getAudience();
        if (aud == null || !aud.contains(clientIdAttendu)) {
            return Optional.empty();
        }
        if (aud.size() > 1 && !clientIdAttendu.equals(jwt.getClaimAsString("azp"))) {
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
    }

    /** Le decodeur, cree au premier besoin ; {@code null} si indisponible (pause, essai deja en cours ou echec). */
    private JwtDecoder decodeurPret() {
        JwtDecoder d = decodeur;
        if (d != null) {
            if (enPause && horloge.getAsLong() - pauseJusqua < 0) {
                return null;
            }
            return d;
        }
        if (enPause && horloge.getAsLong() - pauseJusqua < 0) {
            return null;
        }
        if (!essaiEnCours.compareAndSet(false, true)) {
            return null; // un autre essai est en cours : on ne bloque pas cette requete
        }
        try {
            d = fabrique.get();
            decodeur = d;
            enPause = false;
            return d;
        } catch (RuntimeException e) {
            mettreEnPause("Emetteur Authelia injoignable", e);
            return null;
        } finally {
            essaiEnCours.set(false);
        }
    }

    private void mettreEnPause(String message, RuntimeException e) {
        pauseJusqua = horloge.getAsLong() + PAUSE_ENTRE_ESSAIS_NANOS;
        enPause = true;
        // Seulement le type de l'exception : son message peut citer une adresse ou un fragment de jeton.
        log.warn("{} ({}) : aiguillage indisponible, nouvel essai dans 30 s.", message, e.getClass().getSimpleName());
    }
}
