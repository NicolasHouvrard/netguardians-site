package com.logicielpourtous.site.features.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proprietes authelia posees, emetteur injoignable (port ferme) : le contexte demarre (l'auto-configuration Boot
 * des clients OAuth2 reste inactive), /login repond vite avec le bouton (sans decouverte reseau), et un clic sur
 * le bouton ramene a /login avec un message clair, pas une erreur 500.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConnexionAutheliaInjoignableTest {

    /** Emetteur factice en panne (HTTP 500) qui compte les appels de decouverte. */
    private static final java.util.concurrent.atomic.AtomicInteger APPELS = new java.util.concurrent.atomic.AtomicInteger();
    private static com.sun.net.httpserver.HttpServer emetteur;

    @DynamicPropertySource
    static void proprietes(DynamicPropertyRegistry r) throws IOException {
        emetteur = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        emetteur.createContext("/", ex -> {
            APPELS.incrementAndGet();
            ex.sendResponseHeaders(500, -1);
            ex.close();
        });
        emetteur.start();
        int port = emetteur.getAddress().getPort();
        r.add("spring.security.oauth2.client.registration.authelia.client-id", () -> "site");
        r.add("spring.security.oauth2.client.registration.authelia.client-secret", () -> "pas-un-secret");
        r.add("spring.security.oauth2.client.provider.authelia.issuer-uri", () -> "http://127.0.0.1:" + port);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ClientRegistrationRepository registrations;

    @Test
    void authelia_configure_mais_injoignable_login_repond_vite() throws Exception {
        int avant = APPELS.get();
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/login"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("/oauth2/authorization/authelia")));
        }
        // /login ne touche JAMAIS l'emetteur : le bouton ne depend que des proprietes.
        assertThat(APPELS.get()).isEqualTo(avant);
    }

    @Test
    void l_auto_configuration_boot_reste_inactive_et_le_depot_maison_repond_null_si_injoignable() {
        assertThat(registrations.getClass().getName()).contains("LazyMailAccountRegistrationRepository");
        assertThat(registrations.findByRegistrationId("authelia")).isNull();
    }

    @Test
    void clic_sur_le_bouton_pendant_une_panne_revient_a_la_connexion_avec_un_message() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/authelia"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login?error=authelia"));
        mockMvc.perform(get("/login").param("error", "authelia"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("momentanément indisponible")));
    }
}
