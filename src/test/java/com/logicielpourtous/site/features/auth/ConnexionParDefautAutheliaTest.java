package com.logicielpourtous.site.features.auth;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Compte unique par defaut sur /login, formulaire local relegue a /login/secours (portail https configure). */
@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.authelia.client-id=site-test",
        "spring.security.oauth2.client.registration.authelia.client-secret=pas-un-secret",
        "spring.security.oauth2.client.provider.authelia.issuer-uri=https://auth.example.org/"})
@AutoConfigureMockMvc
class ConnexionParDefautAutheliaTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void avec_authelia_la_page_login_affiche_le_bouton_compte_unique_et_pas_le_formulaire_local() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/oauth2/authorization/authelia\"")))
                .andExpect(content().string(containsString("Vous utilisez le compte unique de Logiciel pour tous.")))
                .andExpect(content().string(not(containsString("name=\"password\""))))
                .andExpect(content().string(not(containsString("name=\"username\""))))
                .andExpect(content().string(not(containsString("cf-turnstile"))));
    }

    @Test
    void le_lien_mot_de_passe_oublie_pointe_vers_le_portail_https_de_la_configuration() throws Exception {
        mockMvc.perform(get("/login").header("Host", "evil.example"))
                .andExpect(content().string(containsString("href=\"https://auth.example.org\"")))
                .andExpect(content().string(not(containsString("evil.example"))));
    }

    @Test
    void la_page_secours_affiche_le_formulaire_local_et_le_champ_secours() throws Exception {
        mockMvc.perform(get("/login/secours"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"username\"")))
                .andExpect(content().string(containsString("name=\"password\"")))
                .andExpect(content().string(containsString("name=\"remember-me\"")))
                .andExpect(content().string(containsString("name=\"secours\"")))
                .andExpect(content().string(containsString("Connexion de secours réservée aux comptes locaux")));
    }

    @Test
    void login_secours_est_public() throws Exception {
        mockMvc.perform(get("/login/secours")).andExpect(status().isOk());
    }

    @Test
    void le_message_authelia_indisponible_ne_parle_plus_du_formulaire() throws Exception {
        mockMvc.perform(get("/login").param("error", "authelia"))
                .andExpect(content().string(containsString("Le compte unique est momentanément indisponible. Réessayez dans quelques instants.")))
                .andExpect(content().string(not(containsString("formulaire ci-dessous"))))
                .andExpect(content().string(containsString("href=\"/login/secours\"")));
    }

    @Test
    void un_echec_depuis_la_page_secours_revient_sur_login_secours_error() throws Exception {
        mockMvc.perform(post("/login").with(csrf()).param("username", "inconnu@example.org")
                        .param("password", "mauvais").param("secours", "1"))
                .andExpect(redirectedUrl("/login/secours?error"));
    }

    @Test
    void un_echec_depuis_login_revient_sur_login_error_comme_avant() throws Exception {
        mockMvc.perform(post("/login").with(csrf()).param("username", "inconnu@example.org")
                        .param("password", "mauvais"))
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void la_valeur_du_champ_secours_ne_peut_pas_servir_de_redirection_ouverte() throws Exception {
        mockMvc.perform(post("/login").with(csrf()).param("username", "inconnu@example.org")
                        .param("password", "mauvais").param("secours", "http://evil.example"))
                .andExpect(redirectedUrl("/login?error"));
    }
}
