package com.logicielpourtous.site.features.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

/** RestSyncClient face a un Maitre en maintenance (503 + Retry-After) : serveur HTTP JDK, aucune attente reelle. */
class RestSyncClientRetryAfterTest {

    private HttpServer serveur;
    private final AtomicInteger appels = new AtomicInteger();
    private volatile int echecs;
    private volatile String retryAfter;
    private final List<Duration> attentes = new ArrayList<>();
    private RestSyncClient client;

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
        client = new RestSyncClient("http://127.0.0.1:" + serveur.getAddress().getPort(), "cle-de-test",
                new ObjectMapper().findAndRegisterModules(), new ReessaiRetryAfter(attentes::add));
    }

    @AfterEach
    void arreter() {
        serveur.stop(0);
    }

    @Test
    void reessaie_apres_503_avec_retry_after() {
        echecs = 2;
        retryAfter = "4";

        assertThat(client.pull(null).events()).isEmpty();

        assertThat(appels.get()).isEqualTo(3);
        assertThat(attentes).containsExactly(Duration.ofSeconds(4), Duration.ofSeconds(4));
    }

    @Test
    void n_attend_pas_plus_de_30_secondes() {
        echecs = 1;
        retryAfter = "86400";

        client.pull(null);

        assertThat(attentes).containsExactly(Duration.ofSeconds(30));
    }

    @Test
    void _503_sans_retry_after_garde_le_comportement_actuel() {
        echecs = 99;
        retryAfter = null;

        assertThatThrownBy(() -> client.pull(null)).isInstanceOf(HttpServerErrorException.class);

        assertThat(appels.get()).isEqualTo(1);
        assertThat(attentes).isEmpty();
    }
}
