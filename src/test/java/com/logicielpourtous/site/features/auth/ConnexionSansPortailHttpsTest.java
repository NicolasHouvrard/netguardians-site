package com.logicielpourtous.site.features.auth;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Emetteur authelia en http : bouton affiche, mais aucun lien « Mot de passe oublie ». */
@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.authelia.client-id=site-test",
        "spring.security.oauth2.client.registration.authelia.client-secret=pas-un-secret",
        "spring.security.oauth2.client.provider.authelia.issuer-uri=http://auth.example.org"})
@AutoConfigureMockMvc
class ConnexionSansPortailHttpsTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void sans_portail_https_pas_de_lien_mot_de_passe_oublie() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/oauth2/authorization/authelia")))
                .andExpect(content().string(not(containsString("Mot de passe oublié"))))
                .andExpect(content().string(not(containsString("auth.example.org"))));
    }
}
