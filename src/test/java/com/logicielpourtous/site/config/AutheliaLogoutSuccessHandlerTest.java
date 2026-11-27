package com.logicielpourtous.site.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

/** Deconnexion : un compte unique (authelia) passe par la page du site, les autres gardent le comportement par defaut. */
class AutheliaLogoutSuccessHandlerTest {

    private static final String PORTAIL = "https://auth.example.org";

    private static Authentication oauth2(String fournisseur) {
        var principal = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of("sub", "alice"), "sub");
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), fournisseur);
    }

    private static Authentication local() {
        return UsernamePasswordAuthenticationToken.authenticated("alice", "x",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static MockHttpServletResponse deconnecter(String issuer, MockHttpServletRequest req, Authentication a)
            throws Exception {
        MockHttpServletResponse rep = new MockHttpServletResponse();
        new AutheliaLogoutSuccessHandler(issuer).onLogoutSuccess(req, rep, a);
        return rep;
    }

    private static MockHttpServletRequest post() {
        return new MockHttpServletRequest("POST", "/logout");
    }

    @Test
    void un_utilisateur_authelia_est_redirige_vers_la_page_de_deconnexion_du_site() throws Exception {
        MockHttpServletResponse rep = deconnecter(PORTAIL + "/", post(), oauth2("authelia"));

        assertThat(rep.getStatus()).isEqualTo(302);
        assertThat(rep.getRedirectedUrl()).isEqualTo("/deconnecte?sso=1");
    }

    @Test
    void un_compte_local_garde_la_redirection_par_defaut() throws Exception {
        assertThat(deconnecter(PORTAIL, post(), local()).getRedirectedUrl()).isEqualTo("/login?logout");
    }

    @Test
    void google_garde_la_redirection_par_defaut() throws Exception {
        assertThat(deconnecter(PORTAIL, post(), oauth2("google")).getRedirectedUrl()).isEqualTo("/login?logout");
    }

    @Test
    void sans_adresse_de_portail_configuree_comportement_par_defaut() throws Exception {
        assertThat(deconnecter("", post(), oauth2("authelia")).getRedirectedUrl()).isEqualTo("/login?logout");
        assertThat(deconnecter(null, post(), oauth2("authelia")).getRedirectedUrl()).isEqualTo("/login?logout");
        assertThat(deconnecter("  ", post(), oauth2("authelia")).getRedirectedUrl()).isEqualTo("/login?logout");
    }

    @Test
    void sans_authentification_comportement_par_defaut() throws Exception {
        assertThat(deconnecter(PORTAIL, post(), null).getRedirectedUrl()).isEqualTo("/login?logout");
    }

    @Test
    void une_adresse_http_est_ignoree() throws Exception {
        assertThat(deconnecter("http://auth.example.org", post(), oauth2("authelia")).getRedirectedUrl())
                .isEqualTo("/login?logout");
    }

    @Test
    void la_cible_ne_depend_jamais_de_la_requete() throws Exception {
        MockHttpServletRequest req = post();
        req.setParameter("rd", "https://pirate.example.net/");
        req.setParameter("redirect", "https://pirate.example.net/");
        req.addHeader("Host", "pirate.example.net");
        req.addHeader("Referer", "https://pirate.example.net/");
        req.addHeader("X-Forwarded-Host", "pirate.example.net");

        assertThat(deconnecter(PORTAIL, req, oauth2("authelia")).getRedirectedUrl()).isEqualTo("/deconnecte?sso=1");
    }

    @Test
    void htmx_recoit_hx_redirect() throws Exception {
        MockHttpServletRequest req = post();
        req.addHeader("HX-Request", "true");

        MockHttpServletResponse rep = deconnecter(PORTAIL, req, oauth2("authelia"));

        assertThat(rep.getStatus()).isEqualTo(200);
        assertThat(rep.getHeader("HX-Redirect")).isEqualTo("/deconnecte?sso=1");
        assertThat(rep.getRedirectedUrl()).isNull();
    }

    @Test
    void sans_marqueur_et_sans_token_authelia_ancien_comportement() throws Exception {
        assertThat(deconnecter(PORTAIL, post(), local()).getRedirectedUrl()).isEqualTo("/login?logout");
        MockHttpServletRequest req = post();
        req.setAttribute(CompteUniqueLogoutHandler.COMPTE_UNIQUE_REQUETE, Boolean.FALSE);
        assertThat(deconnecter(PORTAIL, req, local()).getRedirectedUrl()).isEqualTo("/login?logout");
    }

    @Test
    void l_attribut_de_requete_du_logout_handler_mene_a_la_page_du_site_si_le_portail_est_https() throws Exception {
        MockHttpServletRequest req = post();
        req.setAttribute(CompteUniqueLogoutHandler.COMPTE_UNIQUE_REQUETE, Boolean.TRUE);
        assertThat(deconnecter(PORTAIL, req, local()).getRedirectedUrl()).isEqualTo("/deconnecte?sso=1");

        MockHttpServletRequest htmx = post();
        htmx.addHeader("HX-Request", "true");
        htmx.setAttribute(CompteUniqueLogoutHandler.COMPTE_UNIQUE_REQUETE, Boolean.TRUE);
        assertThat(deconnecter(PORTAIL, htmx, local()).getHeader("HX-Redirect")).isEqualTo("/deconnecte?sso=1");

        MockHttpServletRequest sansPortail = post();
        sansPortail.setAttribute(CompteUniqueLogoutHandler.COMPTE_UNIQUE_REQUETE, Boolean.TRUE);
        assertThat(deconnecter("http://auth.example.org", sansPortail, local()).getRedirectedUrl())
                .isEqualTo("/login?logout");
    }

    @Test
    void le_logout_handler_lit_le_marqueur_avant_l_invalidation_de_la_session() {
        MockHttpServletRequest avec = post();
        avec.getSession().setAttribute(CompteUniqueLogoutHandler.COMPTE_UNIQUE, Boolean.TRUE);
        MockHttpServletRequest sans = post();
        sans.getSession();

        var handler = new CompteUniqueLogoutHandler();
        handler.logout(avec, new MockHttpServletResponse(), local());
        handler.logout(sans, new MockHttpServletResponse(), local());
        handler.logout(post(), new MockHttpServletResponse(), null); // pas de session : aucun effet, pas d'erreur

        assertThat(avec.getAttribute(CompteUniqueLogoutHandler.COMPTE_UNIQUE_REQUETE)).isEqualTo(Boolean.TRUE);
        assertThat(sans.getAttribute(CompteUniqueLogoutHandler.COMPTE_UNIQUE_REQUETE)).isNull();
    }

    // --- Retour sur le site dans le meme onglet (app.auth.authelia.retour-deconnexion) ---

    private static final String RETOUR = "https://master.example.org/login?logout";
    private static final String VERS_PORTAIL =
            "https://auth.example.org/logout?rd=https%3A%2F%2Fmaster.example.org%2Flogin%3Flogout";

    private static MockHttpServletResponse deconnecterAvecRetour(String retour, MockHttpServletRequest req,
                                                                 Authentication a) throws Exception {
        MockHttpServletResponse rep = new MockHttpServletResponse();
        new AutheliaLogoutSuccessHandler(PORTAIL, retour).onLogoutSuccess(req, rep, a);
        return rep;
    }

    @Test
    void un_retour_valide_redirige_vers_la_deconnexion_du_portail_avec_rd_encode() throws Exception {
        MockHttpServletResponse rep = deconnecterAvecRetour(RETOUR, post(), oauth2("authelia"));

        assertThat(rep.getStatus()).isEqualTo(302);
        assertThat(rep.getRedirectedUrl()).isEqualTo(VERS_PORTAIL);

        MockHttpServletRequest req = post();
        req.setAttribute(CompteUniqueLogoutHandler.COMPTE_UNIQUE_REQUETE, Boolean.TRUE);
        assertThat(deconnecterAvecRetour(RETOUR, req, local()).getRedirectedUrl()).isEqualTo(VERS_PORTAIL);
        // le domaine parent lui-meme est accepte
        assertThat(deconnecterAvecRetour("https://example.org/x", post(), oauth2("authelia")).getRedirectedUrl())
                .startsWith("https://auth.example.org/logout?rd=");
    }

    @Test
    void la_redirection_se_fait_dans_le_meme_onglet_donc_sans_page_intermediaire() throws Exception {
        MockHttpServletResponse rep = deconnecterAvecRetour(RETOUR, post(), oauth2("authelia"));

        assertThat(rep.getRedirectedUrl()).doesNotContain("/deconnecte");
        assertThat(rep.getRedirectedUrl()).startsWith("https://auth.example.org/logout");
    }

    @Test
    void htmx_recoit_hx_redirect_vers_le_portail() throws Exception {
        MockHttpServletRequest req = post();
        req.addHeader("HX-Request", "true");

        MockHttpServletResponse rep = deconnecterAvecRetour(RETOUR, req, oauth2("authelia"));

        assertThat(rep.getStatus()).isEqualTo(200);
        assertThat(rep.getHeader("HX-Redirect")).isEqualTo(VERS_PORTAIL);
        assertThat(rep.getRedirectedUrl()).isNull();
    }

    @Test
    void sans_retour_configure_on_garde_la_page_deconnecte() throws Exception {
        assertThat(deconnecterAvecRetour(null, post(), oauth2("authelia")).getRedirectedUrl())
                .isEqualTo("/deconnecte?sso=1");
        assertThat(deconnecterAvecRetour("  ", post(), oauth2("authelia")).getRedirectedUrl())
                .isEqualTo("/deconnecte?sso=1");
    }

    @Test
    void un_retour_http_est_ignore() throws Exception {
        assertThat(deconnecterAvecRetour("http://master.example.org/login?logout", post(), oauth2("authelia"))
                .getRedirectedUrl()).isEqualTo("/deconnecte?sso=1");
    }

    @Test
    void un_retour_hors_du_domaine_parent_est_ignore() throws Exception {
        for (String retour : List.of("https://pirate.example.net/login", "https://example.org.pirate.net/",
                "https://pirateexample.org/", "https://auth.example.org.evil.com/")) {
            assertThat(deconnecterAvecRetour(retour, post(), oauth2("authelia")).getRedirectedUrl())
                    .as(retour).isEqualTo("/deconnecte?sso=1");
        }
        // portail dont le parent n'a qu'un label (« org ») : refuse
        MockHttpServletResponse rep = new MockHttpServletResponse();
        new AutheliaLogoutSuccessHandler("https://example.org", "https://master.example.org/")
                .onLogoutSuccess(post(), rep, oauth2("authelia"));
        assertThat(rep.getRedirectedUrl()).isEqualTo("/deconnecte?sso=1");
    }

    @Test
    void un_retour_avec_identifiants_ou_fragment_est_ignore() throws Exception {
        for (String retour : List.of("https://user:pw@master.example.org/", "https://user@master.example.org/",
                "https://master.example.org/#x", "master.example.org/login", "/login?logout", "not a url")) {
            assertThat(deconnecterAvecRetour(retour, post(), oauth2("authelia")).getRedirectedUrl())
                    .as(retour).isEqualTo("/deconnecte?sso=1");
        }
    }

    @Test
    void le_retour_ne_vient_jamais_de_la_requete() throws Exception {
        MockHttpServletRequest req = post();
        req.setParameter("rd", "https://pirate.example.net/");
        req.setParameter("retour", "https://pirate.example.net/");
        req.addHeader("Host", "pirate.example.net");
        req.addHeader("Referer", "https://pirate.example.net/");
        req.addHeader("X-Forwarded-Host", "pirate.example.net");

        MockHttpServletResponse rep = deconnecterAvecRetour(RETOUR, req, oauth2("authelia"));
        assertThat(rep.getRedirectedUrl()).isEqualTo(VERS_PORTAIL);
        assertThat(rep.getRedirectedUrl()).doesNotContain("pirate");
        assertThat(deconnecterAvecRetour(null, req, oauth2("authelia")).getRedirectedUrl())
                .isEqualTo("/deconnecte?sso=1");
    }

    @Test
    void un_compte_local_garde_la_redirection_par_defaut_meme_avec_un_retour_configure() throws Exception {
        assertThat(deconnecterAvecRetour(RETOUR, post(), local()).getRedirectedUrl()).isEqualTo("/login?logout");
        assertThat(deconnecterAvecRetour(RETOUR, post(), oauth2("google")).getRedirectedUrl())
                .isEqualTo("/login?logout");
    }

    @Test
    void un_portail_non_https_ignore_aussi_le_retour() throws Exception {
        MockHttpServletResponse rep = new MockHttpServletResponse();
        new AutheliaLogoutSuccessHandler("http://auth.example.org", RETOUR)
                .onLogoutSuccess(post(), rep, oauth2("authelia"));
        assertThat(rep.getRedirectedUrl()).isEqualTo("/login?logout");
    }

    @Test
    void l_adresse_de_deconnexion_du_portail_vient_de_la_configuration() {
        assertThat(AutheliaLogoutSuccessHandler.urlDeconnexionPortail("https://auth.example.org/"))
                .isEqualTo("https://auth.example.org/logout");
        assertThat(AutheliaLogoutSuccessHandler.urlDeconnexionPortail("http://auth.example.org")).isNull();
        assertThat(AutheliaLogoutSuccessHandler.urlDeconnexionPortail(null)).isNull();
    }
}
