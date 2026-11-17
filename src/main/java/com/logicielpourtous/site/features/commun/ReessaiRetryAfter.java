package com.logicielpourtous.site.features.commun;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.function.Supplier;

import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClientResponseException;

/**
 * Reessai borne des appels sortants de federation face a un serveur dont un plugin est en
 * maintenance : le serveur repond {@code 503} avec {@code Retry-After} (voir {@code RoutagePlugins}),
 * c'est-a-dire « je n'ai pas traite ta requete, reviens dans N secondes ».
 *
 * <p>Regles : uniquement sur {@code 503} portant un {@code Retry-After} valide (nombre entier de
 * secondes {@code >= 0}, ou date HTTP future ou presente) ; attente bornee a {@link #ATTENTE_MAX} par
 * tentative ; au plus {@link #REESSAIS_MAX} reessais ; ensuite la derniere exception est relancee
 * telle quelle. Sans {@code Retry-After} valide, ou pour tout autre statut / erreur, le comportement
 * est strictement celui d'avant (aucune attente, aucun reessai). Seuls les appels idempotents ou non
 * traites par le serveur sur 503 doivent etre enveloppes : c'est a l'appelant de choisir.
 *
 * <p>L'attente est injectable ({@link Attente}) pour que les tests n'attendent pas reellement. Rien
 * n'est journalise ici (ni en-tete d'authentification, ni corps).
 */
public final class ReessaiRetryAfter {

    public static final Duration ATTENTE_MAX = Duration.ofSeconds(30);
    public static final int REESSAIS_MAX = 2;

    /** Mecanisme d'attente (remplace par les tests). */
    @FunctionalInterface
    public interface Attente {
        void attendre(Duration duree) throws InterruptedException;
    }

    /** Instance de production : attente reelle ({@link Thread#sleep(Duration)}), horloge systeme. */
    public static final ReessaiRetryAfter PAR_DEFAUT = new ReessaiRetryAfter(Thread::sleep, Clock.systemUTC());

    private final Attente attente;
    private final Clock horloge;

    public ReessaiRetryAfter(Attente attente) {
        this(attente, Clock.systemUTC());
    }

    public ReessaiRetryAfter(Attente attente, Clock horloge) {
        this.attente = attente;
        this.horloge = horloge;
    }

    /**
     * Fabrique de requetes a utiliser par les {@code RestClient} de federation. Apache HttpClient 5 (celui
     * que {@code RestClient} choisit par defaut) rejoue de lui-meme, SANS borne, toute reponse 503/429
     * en attendant {@code Retry-After} (jusqu'a l'heure, voire le jour) et meme pour un POST : on
     * desactive ce comportement pour le confier a {@link #executer}, qui est borne. Les reessais sur
     * erreur d'E/S (comportement par defaut du client) sont conserves.
     */
    public static ClientHttpRequestFactory fabriqueRequetes() {
        return new HttpComponentsClientHttpRequestFactory(HttpClients.custom()
                .useSystemProperties()
                .setRetryStrategy(new DefaultHttpRequestRetryStrategy() {
                    @Override
                    public boolean retryRequest(HttpResponse response, int execCount, HttpContext context) {
                        return false;
                    }
                })
                .build());
    }

    /** Execute {@code appel}, en le rejouant (au plus 2 fois) tant que le serveur repond 503 + Retry-After. */
    public <T> T executer(Supplier<T> appel) {
        int reessais = 0;
        while (true) {
            try {
                return appel.get();
            } catch (RestClientResponseException e) {
                Duration delai = delaiRetryAfter(e);
                if (delai == null || reessais >= REESSAIS_MAX) {
                    throw e;
                }
                reessais++;
                try {
                    attente.attendre(delai.compareTo(ATTENTE_MAX) > 0 ? ATTENTE_MAX : delai);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** Delai demande par un 503 ; {@code null} si ce n'est pas un 503 ou si {@code Retry-After} est absent/invalide. */
    private Duration delaiRetryAfter(RestClientResponseException e) {
        if (e.getStatusCode().value() != HttpStatus.SERVICE_UNAVAILABLE.value() || e.getResponseHeaders() == null) {
            return null;
        }
        String valeur = e.getResponseHeaders().getFirst("Retry-After");
        if (valeur == null) {
            return null;
        }
        valeur = valeur.trim();
        if (valeur.matches("\\d{1,9}")) {
            return Duration.ofSeconds(Long.parseLong(valeur));
        }
        try {
            Instant date = ZonedDateTime.parse(valeur, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration delai = Duration.between(horloge.instant(), date);
            return delai.isNegative() ? null : delai;
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
