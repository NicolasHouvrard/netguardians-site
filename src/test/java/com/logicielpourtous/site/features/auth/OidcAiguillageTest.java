package com.logicielpourtous.site.features.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.logicielpourtous.site.features.commun.AiguillageMaitre;
import com.logicielpourtous.site.features.commun.ServerRoleProperties;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;

/**
 * Compte inconnu apres authentification par Authelia : sur un serveur secondaire, le jeton d'identite est garde en
 * session et le gestionnaire d'echec renvoie vers l'aiguillage ; le Maitre, lui, refuse comme avant.
 */
class OidcAiguillageTest {

    /** Valeur fictive : ce n'est pas un vrai jeton. */
    private static final String JETON = "jeton-fictif-pour-test";

    private final UserRepository repo = mock(UserRepository.class);
    private final MockHttpServletRequest requete = new MockHttpServletRequest();

    @BeforeEach
    void contexte() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(requete));
    }

    @AfterEach
    void nettoyer() {
        RequestContextHolder.resetRequestAttributes();
    }

    private OidcSocialLoginUserService service(String role, boolean avecMaitre) {
        ServerRoleProperties props = new ServerRoleProperties();
        props.setRole(role);
        return new OidcSocialLoginUserService(repo, props,
                avecMaitre ? Optional.of(mock(AiguillageMaitre.class)) : Optional.empty());
    }

    private static OidcUser identite(String email, Object emailVerified) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "abc");
        claims.put("email", email);
        if (emailVerified != null) {
            claims.put("email_verified", emailVerified);
        }
        return new DefaultOidcUser(List.of(), new OidcIdToken(JETON, Instant.now(), Instant.now().plusSeconds(60), claims));
    }

    @Test
    void compte_inconnu_sur_un_secondaire_conserve_le_jeton_et_redirige_vers_l_aiguillage() throws Exception {
        when(repo.findAllByEmailIgnoreCase("alice@example.org")).thenReturn(List.of());

        OAuth2AuthenticationException e = org.junit.jupiter.api.Assertions.assertThrows(AiguillageRequis.class,
                () -> service("PARTNER", true).rattacher(identite("alice@example.org", true), "authelia"));

        assertThat(e.getMessage()).isEqualTo(OidcSocialLoginUserService.MESSAGE_REFUS);
        assertThat(requete.getSession(false).getAttribute(AiguillageRequis.ATTRIBUT_JETON)).isEqualTo(JETON);

        MockHttpServletResponse reponse = new MockHttpServletResponse();
        new AiguillageEchecHandler().onAuthenticationFailure(requete, reponse, e);
        assertThat(reponse.getRedirectedUrl()).isEqualTo("/connexion/aiguillage");
    }

    @Test
    void les_autres_echecs_gardent_le_comportement_habituel() throws Exception {
        MockHttpServletResponse reponse = new MockHttpServletResponse();
        new AiguillageEchecHandler().onAuthenticationFailure(requete, reponse,
                new OAuth2AuthenticationException("x"));
        assertThat(reponse.getRedirectedUrl()).isEqualTo("/login?error");
    }

    @Test
    void le_maitre_ne_s_aiguille_pas_lui_meme() {
        when(repo.findAllByEmailIgnoreCase("alice@example.org")).thenReturn(List.of());

        assertThatThrownBy(() -> service("MASTER", true).rattacher(identite("alice@example.org", true), "authelia"))
                .isNotInstanceOf(AiguillageRequis.class)
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessage(OidcSocialLoginUserService.MESSAGE_REFUS);
        assertThat(requete.getSession(false)).isNull();
    }

    @Test
    void sans_appel_possible_au_maitre_ou_hors_authelia_ou_email_non_verifie_ou_compte_inactif_le_refus_reste_generique() {
        when(repo.findAllByEmailIgnoreCase("alice@example.org")).thenReturn(List.of());
        // Pas de client du Maitre configure.
        assertThatThrownBy(() -> service("PARTNER", false).rattacher(identite("alice@example.org", true), "authelia"))
                .isNotInstanceOf(AiguillageRequis.class).hasMessage(OidcSocialLoginUserService.MESSAGE_REFUS);
        // Autre fournisseur que Authelia.
        assertThatThrownBy(() -> service("PARTNER", true).rattacher(identite("alice@example.org", true), "google"))
                .isNotInstanceOf(AiguillageRequis.class).hasMessage(OidcSocialLoginUserService.MESSAGE_REFUS);
        // E-mail non verifie.
        assertThatThrownBy(() -> service("PARTNER", true).rattacher(identite("alice@example.org", false), "authelia"))
                .isNotInstanceOf(AiguillageRequis.class).hasMessage(OidcSocialLoginUserService.MESSAGE_REFUS);
        // Compte inactif : il existe ici, pas d'aiguillage.
        User inactif = new User();
        inactif.setEmail("bob@example.org");
        inactif.setActive(false);
        when(repo.findAllByEmailIgnoreCase("bob@example.org")).thenReturn(List.of(inactif));
        assertThatThrownBy(() -> service("PARTNER", true).rattacher(identite("bob@example.org", true), "authelia"))
                .isNotInstanceOf(AiguillageRequis.class).hasMessage(OidcSocialLoginUserService.MESSAGE_REFUS);
        assertThat(requete.getSession(false)).isNull();
    }
}
