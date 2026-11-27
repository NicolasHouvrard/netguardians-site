package com.logicielpourtous.site.features.auth;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;

import com.logicielpourtous.site.config.CompteUniqueLogoutHandler;
import com.logicielpourtous.site.features.auth.ConfigurationCompteUniqueImpl.ReglagesModifies;

/**
 * Les reglages enregistres en base (sans aucune variable d'environnement) pilotent, sans redemarrage, le bouton de
 * connexion, le lien « mot de passe oublie » et la deconnexion. Adresses factices, aucun appel reseau.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CompteUniqueReglagesEnBaseWebTest {

    private static final String BOUTON = "/oauth2/authorization/authelia";

    @Autowired private MockMvc mockMvc;
    @Autowired private ConfigurationCompteUniqueImpl config;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void nettoyer() {
        jdbc.update("DELETE FROM public.reglages_compte_unique WHERE id = 1");
        config.invalider();
    }

    private void enregistrer(String issuer, String url) {
        config.enregistrer(new ReglagesModifies(issuer, "site-test", "secret-de-test-non-reel-0123456789", url, null, null),
                "test");
    }

    @Test
    void le_bouton_compte_unique_suit_les_reglages_en_base() throws Exception {
        mockMvc.perform(get("/login")).andExpect(status().isOk())
                .andExpect(content().string(not(containsString(BOUTON))));

        enregistrer("https://auth.example.org", null);

        mockMvc.perform(get("/login")).andExpect(status().isOk())
                .andExpect(content().string(containsString(BOUTON)));
    }

    @Test
    void le_lien_mot_de_passe_oublie_suit_l_emetteur_en_base() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(content().string(not(containsString("href=\"https://auth.example.org\""))));

        enregistrer("https://auth.example.org/", null);
        mockMvc.perform(get("/login"))
                .andExpect(content().string(containsString("href=\"https://auth.example.org\"")));

        enregistrer("https://sso.example.net", null);
        mockMvc.perform(get("/login"))
                .andExpect(content().string(containsString("href=\"https://sso.example.net\"")));
    }

    @Test
    void la_deconnexion_utilise_le_retour_deduit_de_l_adresse_publique() throws Exception {
        var jeton = UsernamePasswordAuthenticationToken.authenticated("alice", "x",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(jeton));
        session.setAttribute(CompteUniqueLogoutHandler.COMPTE_UNIQUE, Boolean.TRUE);

        enregistrer("https://auth.example.org", "https://site.example.org");

        mockMvc.perform(post("/logout").session(session).with(csrf()))
                .andExpect(redirectedUrl(
                        "https://auth.example.org/logout?rd=https%3A%2F%2Fsite.example.org%2Flogin%3Flogout"));
    }

    @Test
    void la_page_deconnecte_suit_l_emetteur_en_base() throws Exception {
        mockMvc.perform(get("/deconnecte").param("sso", "1"))
                .andExpect(content().string(not(containsString("Fermer aussi la session du compte unique"))));

        enregistrer("https://auth.example.org", null);

        mockMvc.perform(get("/deconnecte").param("sso", "1"))
                .andExpect(content().string(containsString("Fermer aussi la session du compte unique")));
    }
}
