package com.logicielpourtous.site.features.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.logicielpourtous.site.features.commun.AiguillageMaitre;
import com.logicielpourtous.site.features.commun.AiguillageMaitre.Destination;

/** Aiguillage cote serveur secondaire : decision prise sur la seule liste renvoyee par le Maitre. */
@SpringBootTest
@AutoConfigureMockMvc
class AiguillageControllerTest {

    private static final String JETON = "jeton-fictif-pour-test";
    private static final Destination A = new Destination(1L, "Societe Alpha", "https://alpha.example.org");
    private static final Destination B = new Destination(2L, "Societe Beta", "https://beta.example.org/");

    @Autowired private MockMvc mockMvc;
    @MockitoBean private AiguillageMaitre maitre;

    private static MockHttpSession sessionAvecJeton() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute(AiguillageRequis.ATTRIBUT_JETON, JETON);
        s.setAttribute(AiguillageRequis.ATTRIBUT_JETON_INSTANT, System.currentTimeMillis());
        return s;
    }

    @Test
    void un_seul_serveur_redirige_vers_son_adresse_publique_connexion_authelia() throws Exception {
        when(maitre.aiguiller(JETON)).thenReturn(List.of(A));
        mockMvc.perform(get("/connexion/aiguillage").session(sessionAvecJeton()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://alpha.example.org/oauth2/authorization/authelia"));
    }

    @Test
    void plusieurs_serveurs_affichent_le_choix() throws Exception {
        when(maitre.aiguiller(JETON)).thenReturn(List.of(A, new Destination(3L, "<b>Piege</b>", "https://c.example.org")));
        mockMvc.perform(get("/connexion/aiguillage").session(sessionAvecJeton()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Societe Alpha")))
                // th:text : le nom est echappe, jamais interprete comme du HTML.
                .andExpect(content().string(containsString("&lt;b&gt;Piege&lt;/b&gt;")))
                .andExpect(content().string(not(containsString("<b>Piege</b>"))))
                // Aucune adresse n'est exposee dans la page, seulement des index.
                .andExpect(content().string(not(containsString("alpha.example.org"))))
                .andExpect(content().string(containsString("name=\"index\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @Test
    void le_choix_est_valide_contre_la_liste_pas_contre_une_adresse_fournie() throws Exception {
        when(maitre.aiguiller(JETON)).thenReturn(List.of(A, B));
        MockHttpSession session = sessionAvecJeton();
        mockMvc.perform(get("/connexion/aiguillage").session(session)).andExpect(status().isOk());

        mockMvc.perform(post("/connexion/aiguillage").session(session).with(csrf())
                        .param("index", "1")
                        .param("url", "https://evil.example.com").param("urlPublique", "https://evil.example.com"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://beta.example.org/oauth2/authorization/authelia"));
    }

    @Test
    void un_index_hors_liste_est_refuse() throws Exception {
        when(maitre.aiguiller(JETON)).thenReturn(List.of(A, B));
        for (String index : new String[] {"2", "-1", "999", "abc", ""}) {
            MockHttpSession session = sessionAvecJeton();
            mockMvc.perform(get("/connexion/aiguillage").session(session)).andExpect(status().isOk());
            mockMvc.perform(post("/connexion/aiguillage").session(session).with(csrf()).param("index", index))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl("/login?error=aiguillage"));
        }
        // Sans liste en session (formulaire rejoue ou forge) : meme refus.
        mockMvc.perform(post("/connexion/aiguillage").with(csrf()).param("index", "0"))
                .andExpect(redirectedUrl("/login?error=aiguillage"));
        // Sans jeton CSRF : refuse par la protection CSRF.
        mockMvc.perform(post("/connexion/aiguillage").param("index", "0"))
                .andExpect(status().isForbidden());
    }

    @Test
    void liste_vide_ou_maitre_injoignable_renvoie_au_login_avec_message_neutre() throws Exception {
        when(maitre.aiguiller(JETON)).thenReturn(List.of());
        mockMvc.perform(get("/connexion/aiguillage").session(sessionAvecJeton()))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/login?error=aiguillage"));

        when(maitre.aiguiller(JETON)).thenThrow(new IllegalStateException("Maitre en panne"));
        mockMvc.perform(get("/connexion/aiguillage").session(sessionAvecJeton()))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/login?error=aiguillage"));

        // Sans jeton en session : meme reponse, le Maitre n'est pas appele.
        mockMvc.perform(get("/connexion/aiguillage"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/login?error=aiguillage"));

        mockMvc.perform(get("/login").param("error", "aiguillage"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Connexion impossible avec ce compte.")));
    }

    @Test
    void http_et_double_slash_sont_ignores() throws Exception {
        for (String url : new String[] {"http://alpha.example.org", "//evil.example", "javascript:alert(1)", " ", null,
                "https://user:pw@h.example.org", "https://h.example.org/?x=1", "https:opaque"}) {
            when(maitre.aiguiller(JETON)).thenReturn(List.of(new Destination(1L, "X", url)));
            mockMvc.perform(get("/connexion/aiguillage").session(sessionAvecJeton()))
                    .andExpect(redirectedUrl("/login?error=aiguillage"));
        }
    }

    @Test
    void un_jeton_perime_est_refuse() throws Exception {
        when(maitre.aiguiller(JETON)).thenReturn(List.of(A));
        MockHttpSession perime = sessionAvecJeton();
        perime.setAttribute(AiguillageRequis.ATTRIBUT_JETON_INSTANT, System.currentTimeMillis() - 6 * 60_000L);
        mockMvc.perform(get("/connexion/aiguillage").session(perime))
                .andExpect(redirectedUrl("/login?error=aiguillage"));
        assertThat(perime.getAttribute(AiguillageRequis.ATTRIBUT_JETON)).isNull();
        // Sans horodatage : refuse aussi.
        MockHttpSession sansHorodatage = new MockHttpSession();
        sansHorodatage.setAttribute(AiguillageRequis.ATTRIBUT_JETON, JETON);
        mockMvc.perform(get("/connexion/aiguillage").session(sansHorodatage))
                .andExpect(redirectedUrl("/login?error=aiguillage"));
        verify(maitre, never()).aiguiller(any());
    }

    @Autowired private List<org.springframework.security.web.SecurityFilterChain> chaines;

    @Test
    void le_gestionnaire_d_echec_est_branche_dans_la_chaine_de_securite() throws Exception {
        var filtre = chaines.stream().flatMap(c -> c.getFilters().stream())
                .filter(f -> f instanceof org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter)
                .map(f -> (org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter) f)
                .filter(f -> f.getClass().getSimpleName().equals("OAuth2LoginAuthenticationFilter"))
                .findFirst().orElseThrow();
        var gestionnaire = (org.springframework.security.web.authentication.AuthenticationFailureHandler)
                org.springframework.test.util.ReflectionTestUtils.getField(filtre, "failureHandler");
        var requete = new org.springframework.mock.web.MockHttpServletRequest();
        var reponse = new org.springframework.mock.web.MockHttpServletResponse();
        gestionnaire.onAuthenticationFailure(requete, reponse, new AiguillageRequis());
        assertThat(reponse.getRedirectedUrl()).isEqualTo("/connexion/aiguillage");
    }

    @Test
    void le_jeton_est_retire_de_la_session_apres_usage() throws Exception {
        when(maitre.aiguiller(JETON)).thenReturn(List.of(A));
        MockHttpSession session = sessionAvecJeton();
        mockMvc.perform(get("/connexion/aiguillage").session(session)).andExpect(status().isFound());
        assertThat(session.getAttribute(AiguillageRequis.ATTRIBUT_JETON)).isNull();
        // Rejeu : plus de jeton, le Maitre n'est pas rappele une 2e fois.
        mockMvc.perform(get("/connexion/aiguillage").session(session))
                .andExpect(redirectedUrl("/login?error=aiguillage"));
        verify(maitre, times(1)).aiguiller(any());
        // Jeton retire aussi quand le Maitre echoue.
        when(maitre.aiguiller(JETON)).thenThrow(new IllegalStateException("x"));
        MockHttpSession autre = sessionAvecJeton();
        mockMvc.perform(get("/connexion/aiguillage").session(autre));
        assertThat(autre.getAttribute(AiguillageRequis.ATTRIBUT_JETON)).isNull();
    }

    @Test
    void aucune_adresse_de_redirection_n_est_lue_depuis_les_parametres() throws Exception {
        when(maitre.aiguiller(JETON)).thenReturn(List.of(A));
        mockMvc.perform(get("/connexion/aiguillage").session(sessionAvecJeton())
                        .param("url", "https://evil.example.com").param("redirect", "https://evil.example.com")
                        .param("next", "//evil.example.com").param("urlPublique", "https://evil.example.com"))
                .andExpect(redirectedUrl("https://alpha.example.org/oauth2/authorization/authelia"));
        // Un parametre ne peut pas non plus fournir le jeton.
        mockMvc.perform(get("/connexion/aiguillage").param("idToken", JETON))
                .andExpect(redirectedUrl("/login?error=aiguillage"));
        verify(maitre, times(1)).aiguiller(any());
    }
}
