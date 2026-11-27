package com.logicielpourtous.site.features.licence;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.logicielpourtous.site.features.commun.ConfigurationCompteUnique;

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
 *
 * <p>L'emetteur vient des reglages du compte unique (base d'abord, puis l'environnement) et, a defaut, de l'ancienne
 * propriete {@code app.auth.authelia.issuer-uri}. Quand il change, le decodeur est reconstruit au jeton suivant (avec
 * les memes garanties : pas d'appel au demarrage, un essai a la fois, pause limitee aux pannes d'infrastructure).
 */
@Component
public class ValidateurJetonAuthelia {

    private static final Logger log = LoggerFactory.getLogger(ValidateurJetonAuthelia.class);

    static final long PAUSE_ENTRE_ESSAIS_NANOS = Duration.ofSeconds(30).toNanos();
    private static final Duration DELAI_RESEAU = Duration.ofSeconds(3);

    private final Supplier<String> emetteurSource;
    private final Function<String, JwtDecoder> fabrique;
    private final LongSupplier horloge;
    private final AtomicBoolean essaiEnCours = new AtomicBoolean();
    /** Decodeur construit pour {@link #emetteurDuDecodeur} ; remplace d'un bloc quand l'emetteur change. */
    private volatile JwtDecoder decodeur;
    private volatile String emetteurDuDecodeur = "";
    /** Prochain instant (nanoTime) autorise pour un nouvel essai reseau ; 0 = pas de pause. */
    private volatile long pauseJusqua;
    private volatile boolean enPause;
    /** Emetteur pour lequel la pause a ete decidee : un autre emetteur n'en herite pas. */
    private volatile String emetteurEnPause = "";

    @Autowired
    public ValidateurJetonAuthelia(ConfigurationCompteUnique reglages, Environment env) {
        this(reglages, env, ValidateurJetonAuthelia::fabriqueDeProduction, System::nanoTime);
    }

    /** Emetteur fixe, decodeur de production (tests). */
    ValidateurJetonAuthelia(String emetteur) {
        this(fixe(emetteur), ValidateurJetonAuthelia::fabriqueDeProduction, System::nanoTime);
    }

    /** Pour les tests : decodeur a cles locales (jeu de cles en memoire). */
    ValidateurJetonAuthelia(String emetteur, NimbusJwtDecoder decodeurLocal) {
        this(fixe(emetteur), e -> avecValidation(decodeurLocal, e), System::nanoTime);
    }

    /** Pour les tests : fabrique de decodeur et horloge injectees, emetteur fixe. */
    ValidateurJetonAuthelia(String emetteur, Supplier<JwtDecoder> fabrique, LongSupplier horloge) {
        this(fixe(emetteur), e -> fabrique.get(), horloge);
    }

    /** Pour les tests : reglages vivants, fabrique (recevant l'emetteur) et horloge injectees. */
    ValidateurJetonAuthelia(ConfigurationCompteUnique reglages, Environment env,
                            Function<String, JwtDecoder> fabrique, LongSupplier horloge) {
        this(() -> emetteurEnVigueur(reglages, env), fabrique, horloge);
    }

    private ValidateurJetonAuthelia(Supplier<String> emetteurSource, Function<String, JwtDecoder> fabrique,
                                    LongSupplier horloge) {
        this.emetteurSource = emetteurSource;
        this.fabrique = fabrique;
        this.horloge = horloge;
    }

    private static Supplier<String> fixe(String emetteur) {
        String e = emetteur == null ? "" : emetteur.trim();
        return () -> e;
    }

    /** Reglages d'abord ; a defaut, l'ancienne propriete du Maitre (comportement d'avant les reglages en base). */
    private static String emetteurEnVigueur(ConfigurationCompteUnique reglages, Environment env) {
        String e = reglages.issuerUri();
        if (e == null || e.isBlank()) {
            e = env.getProperty("app.auth.authelia.issuer-uri");
        }
        return e == null ? "" : e.trim();
    }

    private static JwtDecoder fabriqueDeProduction(String emetteur) {
        SimpleClientHttpRequestFactory usine = new SimpleClientHttpRequestFactory();
        usine.setConnectTimeout(DELAI_RESEAU);
        usine.setReadTimeout(DELAI_RESEAU);
        NimbusJwtDecoder d = NimbusJwtDecoder.withIssuerLocation(emetteur)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .restOperations(new RestTemplate(usine))
                .build();
        return avecValidation(d, emetteur);
    }

    private static JwtDecoder avecValidation(NimbusJwtDecoder d, String emetteur) {
        d.setJwtValidator(JwtValidators.createDefaultWithIssuer(emetteur));
        return d;
    }

    /** E-mail en minuscules si le jeton est entierement valide pour {@code clientIdAttendu}, sinon vide. */
    public Optional<String> valider(String idToken, String clientIdAttendu) {
        if (idToken == null || idToken.isBlank() || clientIdAttendu == null || clientIdAttendu.isBlank()) {
            return Optional.empty();
        }
        String emetteur = emetteurSource.get();
        if (emetteur == null || emetteur.isBlank()) {
            return Optional.empty(); // emetteur non configure : desactive, aucun appel reseau
        }
        JwtDecoder d = decodeurPret(emetteur);
        if (d == null) {
            return Optional.empty();
        }
        try {
            return controler(d.decode(idToken.trim()), clientIdAttendu);
        } catch (RuntimeException e) {
            if (panneInfrastructure(e)) {
                // Cles de l'emetteur injoignables : pause globale avant de retenter.
                mettreEnPause(emetteur, "Cles de l'emetteur Authelia indisponibles", e);
            } else {
                // Refus propre a CE jeton (mal forme, signature, dates, cle inconnue...) : aucune pause, sinon un
                // serveur pourrait bloquer l'aiguillage des autres avec des jetons forges. Jamais le message.
                log.debug("Jeton d'identite refuse ({}).", e.getClass().getSimpleName());
            }
            return Optional.empty();
        }
    }

    /** Vrai si la chaine de causes contient une erreur d'E/S ou de recuperation du jeu de cles distant. */
    private static boolean panneInfrastructure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.io.IOException || t instanceof com.nimbusds.jose.RemoteKeySourceException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
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

    /**
     * Le decodeur pour cet emetteur, cree au premier besoin ; {@code null} si indisponible (pause, essai deja en cours
     * ou echec). Un emetteur different de celui du decodeur en place le fait reconstruire, sans pause heritee.
     */
    private JwtDecoder decodeurPret(String emetteur) {
        JwtDecoder d = decodeur;
        boolean memeEmetteur = emetteur.equals(emetteurDuDecodeur);
        boolean pauseApplicable = enPause && emetteur.equals(emetteurEnPause) && horloge.getAsLong() - pauseJusqua < 0;
        if (d != null && memeEmetteur) {
            return pauseApplicable ? null : d;
        }
        if (pauseApplicable) {
            return null;
        }
        if (!essaiEnCours.compareAndSet(false, true)) {
            return null; // un autre essai est en cours : on ne bloque pas cette requete
        }
        try {
            d = fabrique.apply(emetteur);
            decodeur = d;
            emetteurDuDecodeur = emetteur;
            enPause = false;
            return d;
        } catch (RuntimeException e) {
            mettreEnPause(emetteur, "Emetteur Authelia injoignable", e);
            return null;
        } finally {
            essaiEnCours.set(false);
        }
    }

    private void mettreEnPause(String emetteur, String message, RuntimeException e) {
        pauseJusqua = horloge.getAsLong() + PAUSE_ENTRE_ESSAIS_NANOS;
        emetteurEnPause = emetteur;
        enPause = true;
        // Seulement le type de l'exception : son message peut citer une adresse ou un fragment de jeton.
        log.warn("{} ({}) : aiguillage indisponible, nouvel essai dans 30 s.", message, e.getClass().getSimpleName());
    }
}
