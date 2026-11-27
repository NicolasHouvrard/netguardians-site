package com.logicielpourtous.site.config;

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

/** Sans portail Authelia configure : la page /deconnecte (anonyme) ne propose jamais de lien, meme avec sso=1. */
@SpringBootTest
@AutoConfigureMockMvc
class DeconnexionSansPortailWebTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void sans_fournisseur_configure_aucun_lien() throws Exception {
        mockMvc.perform(get("/deconnecte").param("sso", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Fermer aussi la session du compte unique"))))
                .andExpect(content().string(containsString("/login")));
    }
}
