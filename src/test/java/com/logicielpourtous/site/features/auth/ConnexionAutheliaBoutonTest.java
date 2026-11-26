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

/** Sans proprietes authelia : aucun bouton « compte unique » sur la page de connexion. */
@SpringBootTest
@AutoConfigureMockMvc
class ConnexionAutheliaBoutonTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void le_bouton_authelia_n_apparait_que_si_le_fournisseur_est_configure() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("/oauth2/authorization/authelia"))))
                .andExpect(content().string(not(containsString("Se connecter avec le compte unique"))));
    }
}
