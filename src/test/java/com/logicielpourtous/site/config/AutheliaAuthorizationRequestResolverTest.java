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
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
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
}
