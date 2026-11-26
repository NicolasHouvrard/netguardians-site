package com.logicielpourtous.site.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;

/** PKCE S256 pour authelia seulement ; la requete d'autorisation porte la portee openid. */
class AutheliaAuthorizationRequestResolverTest {

    private static ClientRegistration client(String id) {
        return ClientRegistration.withRegistrationId(id)
                .clientId("site").clientSecret("pas-un-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/{action}/oauth2/code/{registrationId}")
                .scope("openid", "email")
                .authorizationUri("https://auth.example.org/authorize")
                .tokenUri("https://auth.example.org/token")
                .build();
    }

    private final AutheliaAuthorizationRequestResolver resolver = new AutheliaAuthorizationRequestResolver(
            new InMemoryClientRegistrationRepository(client("authelia"), client("google")));

    private static MockHttpServletRequest requete(String id) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/oauth2/authorization/" + id);
        r.setServletPath("/oauth2/authorization/" + id);
        return r;
    }

    @Test
    void authelia_utilise_pkce_s256() {
        OAuth2AuthorizationRequest req = resolver.resolve(requete("authelia"));

        assertThat(req).isNotNull();
        assertThat(req.getAdditionalParameters()).containsEntry(PkceParameterNames.CODE_CHALLENGE_METHOD, "S256")
                .containsKey(PkceParameterNames.CODE_CHALLENGE);
        assertThat(req.getAuthorizationRequestUri()).contains("code_challenge=").contains("code_challenge_method=S256");
        assertThat(req.getScopes()).contains("openid");
    }

    @Test
    void les_autres_fournisseurs_gardent_le_comportement_par_defaut_de_spring() {
        // Spring Security 7 applique deja PKCE par defaut : on verifie seulement que les autres fournisseurs
        // ont exactement la meme requete que celle du resolveur par defaut (hors state/nonce/challenge aleatoires).
        var parDefaut = new org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver(
                new InMemoryClientRegistrationRepository(client("authelia"), client("google")), "/oauth2/authorization");
        OAuth2AuthorizationRequest nous = resolver.resolve(requete("google"));
        OAuth2AuthorizationRequest leur = parDefaut.resolve(requete("google"));

        assertThat(nous).isNotNull();
        assertThat(nous.getAdditionalParameters().keySet()).isEqualTo(leur.getAdditionalParameters().keySet());
        assertThat(nous.getScopes()).isEqualTo(leur.getScopes());
        assertThat(nous.getRedirectUri()).isEqualTo(leur.getRedirectUri());
    }

    private static MockHttpServletRequest requeteDerriereProxy(String id) {
        MockHttpServletRequest r = requete(id);
        r.setScheme("http");
        r.setServerName("master.logicielpourtous.com");
        r.setServerPort(80);
        r.addHeader("X-Forwarded-Proto", "https");
        r.addHeader("X-Forwarded-Host", "master.logicielpourtous.com");
        r.addHeader("X-Forwarded-Port", "443");
        return r;
    }

    private static String redirectUri(OAuth2AuthorizationRequest req) {
        return req.getRedirectUri();
    }

    @Test
    void l_adresse_de_retour_est_slash_login_slash_oauth2_slash_code_slash_authelia() {
        // Requete derriere le proxy du VPS (https en amont), comme en production.
        var filtre = new org.springframework.web.filter.ForwardedHeaderFilter();
        MockHttpServletRequest brute = requeteDerriereProxy("authelia");
        brute.setRequestURI("/oauth2/authorization/authelia");
        OAuth2AuthorizationRequest req = resolver.resolve(avecEnTetes(filtre, brute));

        assertThat(redirectUri(req)).isEqualTo("https://master.logicielpourtous.com/login/oauth2/code/authelia");
        assertThat(req.getAuthorizationRequestUri()).contains("code_challenge=").contains("code_challenge_method=S256");
    }

    @Test
    void meme_adresse_de_retour_pour_google_inchange() {
        var filtre = new org.springframework.web.filter.ForwardedHeaderFilter();
        MockHttpServletRequest brute = requeteDerriereProxy("google");
        brute.setRequestURI("/oauth2/authorization/google");
        OAuth2AuthorizationRequest req = resolver.resolve(avecEnTetes(filtre, brute));

        assertThat(redirectUri(req)).isEqualTo("https://master.logicielpourtous.com/login/oauth2/code/google");
    }

    @Test
    void sans_x_forwarded_proto_l_adresse_de_retour_reste_en_http() {
        MockHttpServletRequest r = requete("authelia");
        r.setRequestURI("/oauth2/authorization/authelia");
        r.setServerName("master.logicielpourtous.com");

        assertThat(redirectUri(resolver.resolve(r)))
                .isEqualTo("http://master.logicielpourtous.com/login/oauth2/code/authelia");
    }

    /** Applique ForwardedHeaderFilter (server.forward-headers-strategy=framework) et rend la requete enveloppee. */
    private static jakarta.servlet.http.HttpServletRequest avecEnTetes(
            org.springframework.web.filter.ForwardedHeaderFilter filtre, MockHttpServletRequest brute) {
        java.util.concurrent.atomic.AtomicReference<jakarta.servlet.http.HttpServletRequest> out =
                new java.util.concurrent.atomic.AtomicReference<>();
        try {
            filtre.doFilter(brute, new org.springframework.mock.web.MockHttpServletResponse(), (rq, rs) ->
                    out.set((jakarta.servlet.http.HttpServletRequest) rq));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return out.get();
    }
}
