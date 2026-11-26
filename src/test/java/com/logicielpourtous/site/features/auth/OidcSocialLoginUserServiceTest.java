package com.logicielpourtous.site.features.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;

/** Rattachement d'une identite OpenID Connect (Google, Microsoft, Authelia) a un compte local EXISTANT. */
class OidcSocialLoginUserServiceTest {

    private static final String MESSAGE = "Connexion impossible avec ce compte.";

    private final UserRepository repo = mock(UserRepository.class);
    private final OidcSocialLoginUserService service = new OidcSocialLoginUserService(repo);

    private static OidcUser identite(String email, Object emailVerified) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "abc");
        if (email != null) {
            claims.put("email", email);
        }
        if (emailVerified != null) {
            claims.put("email_verified", emailVerified);
        }
        OidcIdToken jeton = new OidcIdToken("jeton", Instant.now(), Instant.now().plusSeconds(60), claims);
        return new DefaultOidcUser(List.of(), jeton);
    }

    private static User compte(String email, boolean actif) {
        User u = new User();
        u.setEmail(email);
        u.setActive(actif);
        return u;
    }

    private OAuth2AuthenticationException refus(OidcUser identite) {
        return refus(identite, "authelia");
    }

    private OAuth2AuthenticationException refus(OidcUser identite, String registration) {
        try {
            service.rattacher(identite, registration);
        } catch (OAuth2AuthenticationException e) {
            return e;
        }
        throw new AssertionError("refus attendu");
    }

    @Test
    void authelia_refuse_si_email_verified_absent() {
        when(repo.findAllByEmailIgnoreCase("alice@example.org")).thenReturn(List.of(compte("alice@example.org", true)));
        refus(identite("alice@example.org", null), "authelia");
    }

    @Test
    void authelia_refuse_si_email_verified_faux() {
        when(repo.findAllByEmailIgnoreCase("alice@example.org")).thenReturn(List.of(compte("alice@example.org", true)));
        refus(identite("alice@example.org", false), "authelia");
    }

    @Test
    void google_sans_claim_email_verified_est_refuse() {
        when(repo.findAllByEmailIgnoreCase("alice@example.org")).thenReturn(List.of(compte("alice@example.org", true)));
        refus(identite("alice@example.org", null), "google");
        refus(identite("alice@example.org", null), "microsoft");
    }

    @Test
    void fournisseur_inconnu_sans_claim_est_refuse() {
        when(repo.findAllByEmailIgnoreCase("alice@example.org")).thenReturn(List.of(compte("alice@example.org", true)));
        refus(identite("alice@example.org", null), "inconnu");
        refus(identite("alice@example.org", null), null);
    }

    @Test
    void email_verified_faux_est_refuse_pour_tous() {
        when(repo.findAllByEmailIgnoreCase("alice@example.org")).thenReturn(List.of(compte("alice@example.org", true)));
        for (String f : new String[] {"authelia", "google", "microsoft", "inconnu"}) {
            assertThat(refus(identite("alice@example.org", false), f).getMessage()).isEqualTo(MESSAGE);
        }
    }

    @Test
    void email_verified_vrai_est_accepte_pour_google() {
        when(repo.findAllByEmailIgnoreCase("alice@example.org")).thenReturn(List.of(compte("alice@example.org", true)));
        assertThat(service.rattacher(identite("alice@example.org", true), "google")).isInstanceOf(CustomOidcUser.class);
    }

    @Test
    void le_nom_du_fournisseur_est_transmis_a_loadUser() {
        OidcUser id = identite("alice@example.org", true);
        java.util.concurrent.atomic.AtomicReference<String> recu = new java.util.concurrent.atomic.AtomicReference<>();
        OidcSocialLoginUserService svc = new OidcSocialLoginUserService(repo) {
            @Override
            protected OidcUser chargerIdentite(org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest r) {
                return id;
            }

            @Override
            OidcUser rattacher(OidcUser o, String registrationId) {
                recu.set(registrationId);
                return o;
            }
        };
        var client = org.springframework.security.oauth2.client.registration.ClientRegistration.withRegistrationId("authelia")
                .clientId("c").authorizationGrantType(org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://x/cb").authorizationUri("http://x/a").tokenUri("http://x/t").build();
        var jeton = new org.springframework.security.oauth2.core.OAuth2AccessToken(
                org.springframework.security.oauth2.core.OAuth2AccessToken.TokenType.BEARER, "t", Instant.now(), Instant.now().plusSeconds(60));
        svc.loadUser(new org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest(
                client, jeton, ((DefaultOidcUser) id).getIdToken()));
        assertThat(recu.get()).isEqualTo("authelia");
    }

    @Test
    void refuse_si_email_absent() {
        refus(identite(null, true));
        refus(identite("  ", true));
    }

    @Test
    void rattache_par_email_sans_casse_au_compte_existant() {
        User alice = compte("alice@example.org", true);
        when(repo.findAllByEmailIgnoreCase("alice@example.org")).thenReturn(List.of(alice));

        OidcUser resultat = service.rattacher(identite("Alice@Example.ORG", true), "authelia");

        assertThat(resultat).isInstanceOf(CustomOidcUser.class);
        assertThat(((CustomOidcUser) resultat).getUsername()).isEqualTo("alice@example.org");
    }

    @Test
    void refuse_et_ne_cree_rien_pour_un_compte_inconnu() {
        when(repo.findAllByEmailIgnoreCase(any())).thenReturn(List.of());
        refus(identite("inconnu@example.org", true));
        verify(repo, never()).save(any());
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void refuse_un_compte_inactif() {
        when(repo.findAllByEmailIgnoreCase("alice@example.org")).thenReturn(List.of(compte("alice@example.org", false)));
        refus(identite("alice@example.org", true));
    }

    @Test
    void refuse_si_deux_comptes_ne_different_que_par_la_casse() {
        when(repo.findAllByEmailIgnoreCase("alice@example.org"))
                .thenReturn(List.of(compte("alice@example.org", true), compte("Alice@example.org", true)));
        refus(identite("alice@example.org", true));
    }

    @Test
    void le_message_d_erreur_est_identique_dans_tous_les_refus_et_ne_nomme_pas_google() {
        when(repo.findAllByEmailIgnoreCase("inactif@example.org")).thenReturn(List.of(compte("inactif@example.org", false)));
        when(repo.findAllByEmailIgnoreCase("double@example.org"))
                .thenReturn(List.of(compte("double@example.org", true), compte("Double@example.org", true)));
        when(repo.findAllByEmailIgnoreCase("inconnu@example.org")).thenReturn(List.of());
        when(repo.findAllByEmailIgnoreCase("nonverifie@example.org"))
                .thenReturn(List.of(compte("nonverifie@example.org", true)));

        List<OAuth2AuthenticationException> refus = List.of(
                refus(identite(null, true)),
                refus(identite("nonverifie@example.org", false)),
                refus(identite("inconnu@example.org", true)),
                refus(identite("inactif@example.org", true)),
                refus(identite("double@example.org", true)));

        for (OAuth2AuthenticationException e : refus) {
            assertThat(e.getMessage()).isEqualTo(MESSAGE);
            assertThat(e.getError().getErrorCode()).isEqualTo(refus.get(0).getError().getErrorCode());
            assertThat(e.getMessage()).doesNotContainIgnoringCase("google").doesNotContain("@");
        }
    }
}
