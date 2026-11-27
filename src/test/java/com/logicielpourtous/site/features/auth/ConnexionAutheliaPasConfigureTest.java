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

/** Sans proprietes authelia : la page /login reste inchangee (formulaire local, lien de reinitialisation du site). */
@SpringBootTest
@AutoConfigureMockMvc
class ConnexionAutheliaPasConfigureTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void pas_de_lien_de_secours_sans_authelia() throws Exception {
        mockMvc.perform(get("/login")).andExpect(content().string(not(containsString("/login/secours"))));
    }

    @Test
    void sans_authelia_la_page_login_est_inchangee() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"username\"")))
                .andExpect(content().string(containsString("name=\"password\"")))
                .andExpect(content().string(containsString("href=\"/reset-password\"")))
                .andExpect(content().string(not(containsString("/oauth2/authorization/authelia"))))
                .andExpect(content().string(not(containsString("name=\"secours\""))))
                .andExpect(content().string(not(containsString("Vous utilisez le compte unique"))));
    }
}
