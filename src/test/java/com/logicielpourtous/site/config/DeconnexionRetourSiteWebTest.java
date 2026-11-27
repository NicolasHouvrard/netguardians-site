package com.logicielpourtous.site.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;

/** POST /logout dans la vraie chaine, retour de deconnexion configure : retour sur le site dans le meme onglet. */
@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.authelia.client-id=site-test",
        "spring.security.oauth2.client.registration.authelia.client-secret=pas-un-secret",
        "spring.security.oauth2.client.provider.authelia.issuer-uri=https://auth.example.org",
        "app.auth.authelia.retour-deconnexion=https://master.example.org/login?logout"})
@AutoConfigureMockMvc
class DeconnexionRetourSiteWebTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void un_compte_unique_multi_societes_revient_sur_le_site_via_le_portail() throws Exception {
        var jeton = UsernamePasswordAuthenticationToken.authenticated("alice", "x",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(jeton));
        session.setAttribute(CompteUniqueLogoutHandler.COMPTE_UNIQUE, Boolean.TRUE);

        mockMvc.perform(post("/logout").session(session).with(csrf()))
                .andExpect(redirectedUrl(
                        "https://auth.example.org/logout?rd=https%3A%2F%2Fmaster.example.org%2Flogin%3Flogout"));
    }
}
