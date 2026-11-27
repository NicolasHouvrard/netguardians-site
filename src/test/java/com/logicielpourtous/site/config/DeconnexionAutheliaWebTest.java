package com.logicielpourtous.site.config;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;

/**
 * POST /logout et GET /deconnecte dans la vraie chaine de securite, portail Authelia configure (adresse factice,
 * aucun appel reseau : l'enregistrement du fournisseur est paresseux).
 */
@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.authelia.client-id=site-test",
        "spring.security.oauth2.client.registration.authelia.client-secret=pas-un-secret",
        "spring.security.oauth2.client.provider.authelia.issuer-uri=https://auth.example.org"})
@AutoConfigureMockMvc
class DeconnexionAutheliaWebTest {

    private static final String LIEN = "Fermer aussi la session du compte unique";

    @Autowired private MockMvc mockMvc;

    @Test
    void une_session_authelia_est_redirigee_vers_la_page_de_deconnexion_du_site() throws Exception {
        var principal = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of("sub", "alice"), "sub");
        var jeton = new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "authelia");

        mockMvc.perform(post("/logout").with(authentication(jeton)).with(csrf()))
                .andExpect(redirectedUrl("/deconnecte?sso=1"));
    }

    @Test
    void une_session_formulaire_retourne_a_la_page_de_connexion() throws Exception {
        var jeton = UsernamePasswordAuthenticationToken.authenticated("alice", "x",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));

        mockMvc.perform(post("/logout").with(authentication(jeton)).with(csrf()))
                .andExpect(redirectedUrl("/login?logout"));
    }

    @Test
    void apres_choix_de_societe_la_deconnexion_mene_a_deconnecte_sso() throws Exception {
        // Apres /choisir-societe l'authentification n'est plus OAuth2 : seul le marqueur de session subsiste.
        var jeton = UsernamePasswordAuthenticationToken.authenticated("alice", "x",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));

        mockMvc.perform(post("/logout").session(sessionAvec(jeton, true)).with(csrf()))
                .andExpect(redirectedUrl("/deconnecte?sso=1"));
    }

    @Test
    void sans_marqueur_une_session_formulaire_reste_sur_la_page_de_connexion_dans_la_vraie_chaine() throws Exception {
        var jeton = UsernamePasswordAuthenticationToken.authenticated("alice", "x",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));

        mockMvc.perform(post("/logout").session(sessionAvec(jeton, false)).with(csrf()))
                .andExpect(redirectedUrl("/login?logout"));
    }

    private static MockHttpSession sessionAvec(org.springframework.security.core.Authentication jeton,
                                               boolean marqueur) {
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(jeton));
        if (marqueur) {
            session.setAttribute(CompteUniqueLogoutHandler.COMPTE_UNIQUE, Boolean.TRUE);
        }
        return session;
    }

    @Test
    void la_page_deconnecte_affiche_le_lien_authelia_seulement_avec_sso_et_fournisseur_configure() throws Exception {
        mockMvc.perform(get("/deconnecte").param("sso", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Vous êtes déconnecté du site.")))
                .andExpect(content().string(containsString(LIEN)))
                .andExpect(content().string(containsString("Retour à la connexion")));
        mockMvc.perform(get("/deconnecte"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(LIEN))));
        mockMvc.perform(get("/deconnecte").param("sso", "0"))
                .andExpect(content().string(not(containsString(LIEN))));
    }

    @Test
    void le_lien_s_ouvre_dans_un_nouvel_onglet_avec_noopener() throws Exception {
        mockMvc.perform(get("/deconnecte").param("sso", "1"))
                .andExpect(content().string(containsString("href=\"https://auth.example.org/logout\"")))
                .andExpect(content().string(containsString("target=\"_blank\"")))
                .andExpect(content().string(containsString("rel=\"noopener noreferrer\"")));
    }

    @Test
    void l_adresse_du_lien_ne_vient_jamais_de_la_requete() throws Exception {
        mockMvc.perform(get("/deconnecte").param("sso", "1").param("rd", "https://pirate.example.net/")
                        .param("url", "https://pirate.example.net/").header("Referer", "https://pirate.example.net/")
                        .header("X-Forwarded-Host", "pirate.example.net"))
                .andExpect(content().string(not(containsString("pirate.example.net"))))
                .andExpect(content().string(containsString("https://auth.example.org/logout")));
    }
}
