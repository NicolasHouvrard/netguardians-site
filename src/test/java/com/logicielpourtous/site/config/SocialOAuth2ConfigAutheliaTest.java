package com.logicielpourtous.site.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

import com.logicielpourtous.site.features.mail.MailAccountRepository;
import com.sun.net.httpserver.HttpServer;

/** Fournisseur authelia : absent sans proprietes, decouverte paresseuse, un seul essai a la fois. */
class SocialOAuth2ConfigAutheliaTest {

    private HttpServer serveur;
    private final AtomicInteger appels = new AtomicInteger();

    @AfterEach
    void arret() {
        if (serveur != null) {
            serveur.stop(0);
        }
    }

    private String emetteur(long retardMs) throws IOException {
        serveur = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + serveur.getAddress().getPort();
        String json = "{\"issuer\":\"" + base + "\",\"authorization_endpoint\":\"" + base + "/authorize\","
                + "\"token_endpoint\":\"" + base + "/token\",\"jwks_uri\":\"" + base + "/jwks\","
                + "\"userinfo_endpoint\":\"" + base + "/userinfo\",\"subject_types_supported\":[\"public\"],"
                + "\"response_types_supported\":[\"code\"],\"id_token_signing_alg_values_supported\":[\"RS256\"]}";
        serveur.createContext("/.well-known/openid-configuration", ex -> {
            appels.incrementAndGet();
            try {
                Thread.sleep(retardMs);
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
            byte[] corps = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, corps.length);
            ex.getResponseBody().write(corps);
            ex.close();
        });
        serveur.start();
        return base;
    }

    private static MockEnvironment env(String issuer, String scope) {
        MockEnvironment e = new MockEnvironment()
                .withProperty("spring.security.oauth2.client.registration.authelia.client-id", "site")
                .withProperty("spring.security.oauth2.client.registration.authelia.client-secret", "pas-un-secret")
                .withProperty("spring.security.oauth2.client.provider.authelia.issuer-uri", issuer);
        if (scope != null) {
            e.setProperty("spring.security.oauth2.client.registration.authelia.scope", scope);
        }
        return e;
    }

    @Test
    void sans_proprietes_authelia_est_absent() {
        ClientRegistrationRepository repo = new SocialOAuth2Config(mock(MailAccountRepository.class), new MockEnvironment())
                .clientRegistrationRepository();
        assertThat(repo.findByRegistrationId("authelia")).isNull();
        assertThat(repo.findByRegistrationId("google")).isNull();
        assertThat(SocialOAuth2Config.autheliaConfigure(new MockEnvironment())).isFalse();
    }

    @Test
    void proprietes_vides_ou_partielles_authelia_est_absent() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.security.oauth2.client.registration.authelia.client-id", "site")
                .withProperty("spring.security.oauth2.client.registration.authelia.client-secret", "")
                .withProperty("spring.security.oauth2.client.provider.authelia.issuer-uri", "");
        assertThat(SocialOAuth2Config.autheliaConfigure(env)).isFalse();
        assertThat(new SocialOAuth2Config(mock(MailAccountRepository.class), env).clientRegistrationRepository()
                .findByRegistrationId("authelia")).isNull();
    }

    @Test
    void configure_et_joignable_la_registration_est_batie_avec_openid_toujours_present() throws Exception {
        String base = emetteur(0);
        // portee configuree SANS openid : openid est ajoute
        ClientRegistration r = new SocialOAuth2Config.AutheliaRegistration(env(base, "email groups")).get();

        assertThat(r).isNotNull();
        assertThat(r.getRegistrationId()).isEqualTo("authelia");
        assertThat(r.getClientId()).isEqualTo("site");
        assertThat(r.getClientSecret()).isEqualTo("pas-un-secret");
        assertThat(r.getScopes()).contains("openid", "email", "groups");
        assertThat(r.getProviderDetails().getAuthorizationUri()).isEqualTo(base + "/authorize");
        assertThat(SocialOAuth2Config.autheliaConfigure(env(base, null))).isTrue();
    }

    @Test
    void injoignable_donne_null_puis_nouvel_essai_apres_le_delai() throws Exception {
        String base = emetteur(0);
        serveur.stop(0);
        SocialOAuth2Config.AutheliaRegistration reg = new SocialOAuth2Config.AutheliaRegistration(env(base, null), 1_000, 200);
        assertThat(reg.get()).isNull();
        assertThat(reg.get()).isNull(); // dans le delai d'attente : pas de nouvel essai
        Thread.sleep(300);
        serveur = null;
        // l'emetteur revient sur le meme port : nouvel essai possible
        int port = Integer.parseInt(base.substring(base.lastIndexOf(':') + 1));
        serveur = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        serveur.createContext("/.well-known/openid-configuration", ex -> {
            String json = "{\"issuer\":\"" + base + "\",\"authorization_endpoint\":\"" + base + "/authorize\","
                    + "\"token_endpoint\":\"" + base + "/token\",\"jwks_uri\":\"" + base + "/jwks\","
                    + "\"subject_types_supported\":[\"public\"]}";
            byte[] corps = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, corps.length);
            ex.getResponseBody().write(corps);
            ex.close();
        });
        serveur.start();
        assertThat(reg.get()).isNotNull();
    }

    @Test
    void un_seul_essai_de_decouverte_a_la_fois() throws Exception {
        String base = emetteur(1_500);
        SocialOAuth2Config.AutheliaRegistration reg = new SocialOAuth2Config.AutheliaRegistration(env(base, null), 5_000, 30_000);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch debut = new CountDownLatch(1);
            Future<ClientRegistration> premier = pool.submit(() -> {
                debut.countDown();
                return reg.get();
            });
            debut.await();
            Thread.sleep(300); // le premier essai est en cours
            long t0 = System.nanoTime();
            ClientRegistration second = pool.submit(reg::get).get(1, TimeUnit.SECONDS);
            long ms = (System.nanoTime() - t0) / 1_000_000;

            assertThat(second).isNull(); // refoule aussitot, sans attendre la reponse lente
            assertThat(ms).isLessThan(1_000);
            assertThat(premier.get(10, TimeUnit.SECONDS)).isNotNull();
            assertThat(appels.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
