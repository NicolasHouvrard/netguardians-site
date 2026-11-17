package com.logicielpourtous.site.features.commun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

/** Reessai borne sur 503 + Retry-After (federation face a un plugin en maintenance). */
class ReessaiRetryAfterTest {

    private final List<Duration> attentes = new ArrayList<>();
    private final Instant maintenant = Instant.parse("2026-11-17T10:00:00Z");
    private final ReessaiRetryAfter reessai =
            new ReessaiRetryAfter(attentes::add, Clock.fixed(maintenant, ZoneOffset.UTC));

    private static HttpServerErrorException erreur503(String retryAfter) {
        HttpHeaders h = new HttpHeaders();
        if (retryAfter != null) {
            h.set("Retry-After", retryAfter);
        }
        return HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "maintenance", h, new byte[0],
                StandardCharsets.UTF_8);
    }

    /** Appel qui echoue {@code echecs} fois avec la meme 503, puis reussit. */
    private static Supplier<String> appel(AtomicInteger compteur, int echecs, String retryAfter) {
        return () -> {
            if (compteur.incrementAndGet() <= echecs) {
                throw erreur503(retryAfter);
            }
            return "ok";
        };
    }

    @Test
    void reessaie_apres_503_avec_retry_after() {
        AtomicInteger n = new AtomicInteger();

        assertThat(reessai.executer(appel(n, 1, "3"))).isEqualTo("ok");

        assertThat(n.get()).isEqualTo(2);
        assertThat(attentes).containsExactly(Duration.ofSeconds(3));
    }

    @Test
    void n_attend_pas_plus_de_30_secondes() {
        AtomicInteger n = new AtomicInteger();

        assertThat(reessai.executer(appel(n, 1, "3600"))).isEqualTo("ok");

        assertThat(attentes).containsExactly(Duration.ofSeconds(30));
    }

    @Test
    void retry_after_en_date_http() {
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(maintenant.plusSeconds(7).atOffset(ZoneOffset.UTC));
        AtomicInteger n = new AtomicInteger();

        assertThat(reessai.executer(appel(n, 1, date))).isEqualTo("ok");

        assertThat(attentes).containsExactly(Duration.ofSeconds(7));
    }

    @Test
    void s_arrete_apres_deux_reessais_et_relance_la_derniere_erreur() {
        AtomicInteger n = new AtomicInteger();

        assertThatThrownBy(() -> reessai.executer(appel(n, 99, "1")))
                .isInstanceOf(HttpServerErrorException.class);

        assertThat(n.get()).isEqualTo(3); // 1 appel + 2 reessais
        assertThat(attentes).hasSize(2);
    }

    @Test
    void _503_sans_retry_after_garde_le_comportement_actuel() {
        AtomicInteger n = new AtomicInteger();

        assertThatThrownBy(() -> reessai.executer(appel(n, 99, null)))
                .isInstanceOf(HttpServerErrorException.class);

        assertThat(n.get()).isEqualTo(1);
        assertThat(attentes).isEmpty();
    }

    @Test
    void retry_after_invalide_ou_negatif_est_ignore() {
        for (String valeur : new String[] {"abc", "-5", "", "1.5", "Mon, 99 Foo 2026"}) {
            AtomicInteger n = new AtomicInteger();
            assertThatThrownBy(() -> reessai.executer(appel(n, 99, valeur)))
                    .as("Retry-After=%s", valeur)
                    .isInstanceOf(HttpServerErrorException.class);
            assertThat(n.get()).as("Retry-After=%s", valeur).isEqualTo(1);
        }
        assertThat(attentes).isEmpty();
    }

    @Test
    void autre_statut_que_503_n_est_jamais_reessaye() {
        AtomicInteger n = new AtomicInteger();
        Supplier<String> appel = () -> {
            n.incrementAndGet();
            HttpHeaders h = new HttpHeaders();
            h.set("Retry-After", "1");
            throw HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "x", h, new byte[0],
                    StandardCharsets.UTF_8);
        };

        assertThatThrownBy(() -> reessai.executer(appel)).isInstanceOf(HttpClientErrorException.class);

        assertThat(n.get()).isEqualTo(1);
        assertThat(attentes).isEmpty();
    }
}
