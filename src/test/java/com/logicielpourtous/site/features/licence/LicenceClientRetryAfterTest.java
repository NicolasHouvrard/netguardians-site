package com.logicielpourtous.site.features.licence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpServerErrorException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.logicielpourtous.site.features.commun.ReessaiRetryAfter;
import com.sun.net.httpserver.HttpServer;

/** LicenceClient face a un Maitre en maintenance (503 + Retry-After) : serveur HTTP JDK, aucune attente reelle. */
class LicenceClientRetryAfterTest {

    private HttpServer serveur;
    private final AtomicInteger appels = new AtomicInteger();
    private volatile int echecs;
    private volatile String retryAfter;
    private final List<Duration> attentes = new ArrayList<>();
    private LicenceClient client;

    @BeforeEach
    void demarrer() throws IOException {
        serveur = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serveur.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            byte[] corps = "[]".getBytes();
            if (appels.incrementAndGet() <= echecs) {
                if (retryAfter != null) {
                    ex.getResponseHeaders().set("Retry-After", retryAfter);
                }
                ex.sendResponseHeaders(503, -1);
            } else {
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(200, corps.length);
                ex.getResponseBody().write(corps);
            }
            ex.close();
        });
        serveur.start();
        EtatLicenceLocaleRepository repo = mock(EtatLicenceLocaleRepository.class);
        EtatLicenceLocale etat = new EtatLicenceLocale();
        etat.setCleApiLicence("cle-de-test");
        when(repo.findAll()).thenReturn(List.of(etat));
        client = new LicenceClient("http://127.0.0.1:" + serveur.getAddress().getPort(), repo, new ObjectMapper(),
                new ReessaiRetryAfter(attentes::add));
    }

    @AfterEach
    void arreter() {
        serveur.stop(0);
    }

    @Test
    void reessaie_apres_503_avec_retry_after() {
        echecs = 1;
        retryAfter = "2";

        assertThat(client.listerNouveauxServeurs()).isEmpty();

        assertThat(appels.get()).isEqualTo(2);
        assertThat(attentes).containsExactly(Duration.ofSeconds(2));
    }

    @Test
    void _503_sans_retry_after_garde_le_comportement_actuel() {
        echecs = 99;
        retryAfter = null;

        assertThatThrownBy(() -> client.listerNouveauxServeurs()).isInstanceOf(HttpServerErrorException.class);

        assertThat(appels.get()).isEqualTo(1);
        assertThat(attentes).isEmpty();
    }

    @Test
    void ne_reessaie_pas_la_generation_de_jeton_non_idempotente() {
        echecs = 99;
        retryAfter = "1";

        assertThatThrownBy(() -> client.genererJeton(1L)).isInstanceOf(HttpServerErrorException.class);

        assertThat(appels.get()).isEqualTo(1);
        assertThat(attentes).isEmpty();
    }
}
