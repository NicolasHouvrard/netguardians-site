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

    private static Resultat avecAdresse(String litterale) throws Exception {
        byte[] octets = InetAddress.getByName(litterale).getAddress();
        return new TestConnexionAuthelia(uri -> new Reponse(200, DOC),
                h -> new InetAddress[] { InetAddress.getByAddress(h, octets) }).tester("https://auth.example.org");
    }

    @Test
    void seules_les_adresses_globales_unicast_sont_acceptees() throws Exception {
        for (String a : List.of("64:ff9b::808:808", "64:ff9b::a00:1", "64:ff9b:1::1", "2002:808:808::1",
                "2001::1", "2001:0:4136:e378:8000:63bf:3fff:fdd2", "::10.0.0.1", "::8.8.8.8", "::ffff:10.0.0.1",
                "::ffff:127.0.0.1", "::ffff:169.254.169.254", "fe80::1", "fc00::1", "fd12::1", "ff02::1", "::1", "::",
                "2001:db8::1", "3fff::1", "169.254.169.254", "100.64.0.1", "100.127.255.254", "192.0.2.1",
                "198.51.100.1", "203.0.113.1", "224.0.0.1", "0.0.0.0", "255.255.255.255", "172.16.0.1", "192.168.0.1",
                "10.1.2.3", "127.0.0.1", "198.18.0.1")) {
            assertThat(avecAdresse(a)).as(a).isEqualTo(Resultat.ADRESSE_REFUSEE);
        }
        for (String a : List.of("8.8.8.8", "93.184.216.34", "::ffff:8.8.8.8", "2606:2800:220:1:248:1893:25c8:1946",
                "2a00:1450:4007:80f::200e")) {
            assertThat(avecAdresse(a)).as(a).isEqualTo(Resultat.JOIGNABLE);
        }
    }

    @Test
    void seul_le_port_443_est_accepte() {
        List<URI> appels = new ArrayList<>();
        TestConnexionAuthelia t = avec(appels, new Reponse(200, DOC));
        assertThat(t.tester("https://auth.example.org:8443")).isEqualTo(Resultat.ADRESSE_REFUSEE);
        assertThat(t.tester("https://auth.example.org:22")).isEqualTo(Resultat.ADRESSE_REFUSEE);
        assertThat(appels).isEmpty();
        assertThat(t.tester("https://auth.example.org:443")).isEqualTo(Resultat.JOIGNABLE);
        assertThat(t.tester("https://auth.example.org")).isEqualTo(Resultat.JOIGNABLE);
    }

    @Test
    void la_resolution_dns_est_bornee_par_le_delai_global() {
        TestConnexionAuthelia lent = new TestConnexionAuthelia(uri -> new Reponse(200, DOC), h -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return publique(h);
        }, Duration.ofMillis(200));
        long debut = System.nanoTime();
        assertThat(lent.tester("https://auth.example.org")).isEqualTo(Resultat.INJOIGNABLE);
        assertThat(Duration.ofNanos(System.nanoTime() - debut)).isLessThan(Duration.ofMillis(1000));
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
