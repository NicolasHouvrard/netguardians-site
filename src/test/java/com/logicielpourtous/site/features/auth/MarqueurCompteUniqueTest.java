package com.logicielpourtous.site.features.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.util.ReflectionTestUtils;

import com.logicielpourtous.site.config.CompteUniqueLogoutHandler;
import com.logicielpourtous.site.config.JwtTokenProvider;
import com.logicielpourtous.site.features.audit.AuditLogService;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantContext;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.user.Appartenance;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;

/**
 * Marqueur de session « compte unique » : pose a la seule connexion reussie par le fournisseur authelia
 * (sans Spring : mocks). Il ne donne aucun droit, il sert a choisir la page publique de fin de deconnexion.
 */
class MarqueurCompteUniqueTest {

    private CustomTenantSuccessHandler handler;
    private CustomUserDetails enAttente;

    @BeforeEach
    void preparer() {
        UserRepository userRepository = mock(UserRepository.class);
        CustomUserDetailsService userDetailsService = mock(CustomUserDetailsService.class);
        JwtTokenProvider jwt = mock(JwtTokenProvider.class);
        when(jwt.generate(any())).thenReturn("jeton");
        handler = new CustomTenantSuccessHandler(userRepository, userDetailsService, jwt, mock(AuditLogService.class));
        ReflectionTestUtils.setField(handler, "angularLoginRedirect", "http://angular/login");

        Tenant alpha = tenant(2, "schema_alpha");
        User user = new User();
        ReflectionTestUtils.setField(user, "id", 1L);
        user.setEmail("p@exemple.fr");
        user.setPassword("x");
        user.setActive(true);
        user.setTenant(alpha);
        user.setRoles(Set.of(new Role("ADMIN", "Admin", "ESN")));
        when(userRepository.findByEmail("p@exemple.fr")).thenReturn(Optional.of(user));
        enAttente = new CustomUserDetails(user, List.of(
                appartenance(10, alpha, new Role("ADMIN", "Admin", "ESN")),
                appartenance(11, tenant(3, "schema_beta"), new Role("CLIENT", "Client", "CLIENT"))), null);
        when(userDetailsService.loadUserByUsername("p@exemple.fr")).thenReturn(enAttente);
    }

    @AfterEach
    void nettoyer() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private static Authentication oauth2(String fournisseur) {
        var principal = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of("sub", "p@exemple.fr"), "sub");
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), fournisseur);
    }

    @Test
    void la_connexion_authelia_pose_le_marqueur_en_session() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();

        handler.onAuthenticationSuccess(req, new MockHttpServletResponse(), oauth2("authelia"));

        assertThat(req.getSession().getAttribute(CompteUniqueLogoutHandler.COMPTE_UNIQUE)).isEqualTo(Boolean.TRUE);
    }

    @Test
    void un_compte_local_ou_google_n_a_pas_le_marqueur() throws Exception {
        MockHttpServletRequest local = new MockHttpServletRequest();
        handler.onAuthenticationSuccess(local, new MockHttpServletResponse(),
                UsernamePasswordAuthenticationToken.authenticated(enAttente, null, List.of()));
        MockHttpServletRequest google = new MockHttpServletRequest();
        handler.onAuthenticationSuccess(google, new MockHttpServletResponse(), oauth2("google"));
        MockHttpServletRequest microsoft = new MockHttpServletRequest();
        handler.onAuthenticationSuccess(microsoft, new MockHttpServletResponse(), oauth2("microsoft"));

        assertThat(local.getSession().getAttribute(CompteUniqueLogoutHandler.COMPTE_UNIQUE)).isNull();
        assertThat(google.getSession().getAttribute(CompteUniqueLogoutHandler.COMPTE_UNIQUE)).isNull();
        assertThat(microsoft.getSession().getAttribute(CompteUniqueLogoutHandler.COMPTE_UNIQUE)).isNull();
    }

    private static Tenant tenant(long id, String schema) {
        Tenant t = new Tenant();
        ReflectionTestUtils.setField(t, "id", id);
        t.setName("T" + id);
        t.setSchemaName(schema);
        return t;
    }

    private static Appartenance appartenance(long id, Tenant t, Role role) {
        Appartenance a = new Appartenance();
        a.setId(id);
        a.setUserId(1L);
        a.setTenant(t);
        a.setRoles(Set.of(role));
        return a;
    }
}
