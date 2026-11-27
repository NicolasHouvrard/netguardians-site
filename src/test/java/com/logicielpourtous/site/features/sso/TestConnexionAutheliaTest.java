package com.logicielpourtous.site.features.sso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.logicielpourtous.site.features.sso.TestConnexionAuthelia.Reponse;
import com.logicielpourtous.site.features.sso.TestConnexionAuthelia.Resultat;
import com.sun.net.httpserver.HttpServer;

/** Test de connexion a l'emetteur : https seulement, delais bornes, aucune redirection, jamais le corps. */
class TestConnexionAutheliaTest {

    private static final String DOC = "{\"issuer\":\"https://auth.example.org\",\"authorization_endpoint\":\"x\"}";

    private static InetAddress[] publique(String hote) throws UnknownHostException {
        return new InetAddress[] { InetAddress.getByAddress(hote, new byte[] { (byte) 93, (byte) 184, (byte) 216, 34 }) };
    }

    private static TestConnexionAuthelia avec(List<URI> appels, Reponse reponse) {
        return new TestConnexionAuthelia(uri -> {
            appels.add(uri);
            return reponse;
        }, TestConnexionAutheliaTest::publique);
    }

    @Test
    void le_test_de_connexion_refuse_http_et_borne_les_delais() {
        List<URI> appels = new ArrayList<>();
        TestConnexionAuthelia t = avec(appels, new Reponse(200, DOC));

        assertThat(t.tester("http://auth.example.org")).isEqualTo(Resultat.ADRESSE_REFUSEE);
        assertThat(t.tester("https://user:pw@auth.example.org")).isEqualTo(Resultat.ADRESSE_REFUSEE);
        assertThat(t.tester("")).isEqualTo(Resultat.ADRESSE_REFUSEE);
        assertThat(appels).isEmpty();

        assertThat(t.tester("https://auth.example.org/")).isEqualTo(Resultat.JOIGNABLE);
        assertThat(appels).containsExactly(URI.create("https://auth.example.org/.well-known/openid-configuration"));

        assertThat(TestConnexionAuthelia.DELAI).isLessThanOrEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void le_test_refuse_les_hotes_ip_locaux_et_les_noms_resolvant_vers_le_prive() {
        List<URI> appels = new ArrayList<>();
        TestConnexionAuthelia t = avec(appels, new Reponse(200, DOC));
        for (String a : List.of("https://127.0.0.1", "https://10.0.0.5", "https://[::1]", "https://localhost",
                "https://serveur", "https://nas.local", "https://auth.internal")) {
            assertThat(t.tester(a)).as(a).isEqualTo(Resultat.ADRESSE_REFUSEE);
        }
        TestConnexionAuthelia prive = new TestConnexionAuthelia(uri -> {
            appels.add(uri);
            return new Reponse(200, DOC);
        }, h -> new InetAddress[] { InetAddress.getByAddress(h, new byte[] { 10, 0, 0, 7 }) });
        assertThat(prive.tester("https://auth.example.org")).isEqualTo(Resultat.ADRESSE_REFUSEE);
        TestConnexionAuthelia mixte = new TestConnexionAuthelia(uri -> {
            appels.add(uri);
            return new Reponse(200, DOC);
        }, h -> new InetAddress[] { publique(h)[0],
                InetAddress.getByAddress(h, new byte[] { (byte) 192, (byte) 168, 1, 1 }) });
        assertThat(mixte.tester("https://auth.example.org")).isEqualTo(Resultat.ADRESSE_REFUSEE);
        assertThat(appels).isEmpty();
    }

    @Test
    void les_reponses_sont_classees_sans_jamais_renvoyer_le_corps() {
        List<URI> appels = new ArrayList<>();
        assertThat(avec(appels, new Reponse(200, "<html>pas du json</html>")).tester("https://auth.example.org"))
                .isEqualTo(Resultat.REPONSE_INATTENDUE);
        assertThat(avec(appels, new Reponse(200, "{\"autre\":1}")).tester("https://auth.example.org"))
                .isEqualTo(Resultat.REPONSE_INATTENDUE);
        assertThat(avec(appels, new Reponse(302, "")).tester("https://auth.example.org"))
                .isEqualTo(Resultat.REPONSE_INATTENDUE);
        assertThat(avec(appels, new Reponse(500, DOC)).tester("https://auth.example.org"))
                .isEqualTo(Resultat.REPONSE_INATTENDUE);
        TestConnexionAuthelia panne = new TestConnexionAuthelia(uri -> {
            throw new IOException("connexion refusee");
        }, TestConnexionAutheliaTest::publique);
        assertThat(panne.tester("https://auth.example.org")).isEqualTo(Resultat.INJOIGNABLE);
        TestConnexionAuthelia inconnu = new TestConnexionAuthelia(uri -> new Reponse(200, DOC), h -> {
            throw new UnknownHostException(h);
        });
        assertThat(inconnu.tester("https://auth.example.org")).isEqualTo(Resultat.INJOIGNABLE);
    }

    @Test
    void le_transport_reel_ne_suit_aucune_redirection_et_borne_le_delai() throws Exception {
        AtomicInteger cible = new AtomicInteger();
        HttpServer serveur = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        serveur.createContext("/redir", ex -> {
            ex.getResponseHeaders().add("Location", "/cible");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        serveur.createContext("/cible", ex -> {
            cible.incrementAndGet();
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        serveur.createContext("/lent", ex -> {
            try {
                Thread.sleep(2500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        serveur.start();
        try {
            String base = "http://127.0.0.1:" + serveur.getAddress().getPort();
            TestConnexionAuthelia.TransportHttp transport =
                    new TestConnexionAuthelia.TransportHttp(Duration.ofMillis(400));

            Reponse r = transport.get(URI.create(base + "/redir"));
            assertThat(r.statut()).isEqualTo(302);
            assertThat(cible.get()).isZero();

            long debut = System.nanoTime();
            assertThrows(IOException.class, () -> transport.get(URI.create(base + "/lent")));
            assertThat(Duration.ofNanos(System.nanoTime() - debut)).isLessThan(Duration.ofMillis(2000));
        } finally {
            serveur.stop(0);
        }
    }
}
