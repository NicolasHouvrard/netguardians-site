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
    private volatile String reponse = "[]";
    private final List<String> corpsRecus = new java.util.concurrent.CopyOnWriteArrayList<>();
    private RestSyncClient client;

    @BeforeEach
    void demarrer() throws IOException {
        serveur = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serveur.createContext("/", ex -> {
            corpsRecus.add(new String(ex.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            byte[] corps = reponse.getBytes();
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

    /** Nom du brief : 503_sans_retry_after_garde_le_comportement_actuel (identifiant Java invalide). */
    @Test
    void echoue_tout_de_suite_sur_503_sans_retry_after() {
        echecs = 99;
        retryAfter = null;

        assertThatThrownBy(() -> client.pull(null)).isInstanceOf(HttpServerErrorException.class);

        assertThat(appels.get()).isEqualTo(1);
        assertThat(attentes).isEmpty();
    }

    @Test
    void push_reessaie_apres_503_avec_le_meme_corps() {
        echecs = 1;
        retryAfter = "1";
        reponse = "{\"resultats\":[]}";

        assertThat(client.push(List.of())).isEmpty();

        assertThat(appels.get()).isEqualTo(2);
        assertThat(attentes).containsExactly(Duration.ofSeconds(1));
        assertThat(corpsRecus).hasSize(2);
        assertThat(corpsRecus.get(0)).isEqualTo(corpsRecus.get(1));
    }

    /** Le rejeu par defaut du client Apache sur erreur d'entree-sortie (connexion coupee) est conserve. */
    @Test
    void reessai_sur_erreur_d_es_conserve() throws Exception {
        serveur.stop(0);
        try (java.net.ServerSocket brut = new java.net.ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress())) {
            Thread t = new Thread(() -> {
                try {
                    // 1re connexion : lue puis coupee sans reponse (erreur d'E/S cote client).
                    try (java.net.Socket s = brut.accept()) {
                        s.getInputStream().read(new byte[4096]);
                    }
                    try (java.net.Socket s = brut.accept()) {
                        s.getInputStream().read(new byte[4096]);
                        s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                                + "Content-Length: 2\r\nConnection: close\r\n\r\n[]").getBytes());
                        s.getOutputStream().flush();
                    }
                } catch (IOException ignore) {
                    // le test echouera sur l'assertion
                }
            });
            t.setDaemon(true);
            t.start();
            RestSyncClient c = new RestSyncClient("http://127.0.0.1:" + brut.getLocalPort(), "cle-de-test",
                    new ObjectMapper().findAndRegisterModules(), new ReessaiRetryAfter(attentes::add));

            assertThat(c.pull(null).events()).isEmpty();
            assertThat(attentes).isEmpty();
        }
    }
}
