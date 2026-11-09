package com.logicielpourtous.site.features.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.util.ReflectionTestUtils;

import com.logicielpourtous.site.config.JwtTokenProvider;
import com.logicielpourtous.site.features.audit.AuditLogService;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantContext;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.user.Appartenance;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;

import jakarta.servlet.http.Cookie;

/**
 * Redirection apres connexion web d'une personne qui appartient a plusieurs societes : elle doit d'abord
 * choisir sa societe, sans schema de tenant ni jeton tant qu'elle n'a pas choisi (sans Spring : mocks).
 */
class CustomTenantSuccessHandlerSelectionTest {

    private UserRepository userRepository;
    private CustomUserDetailsService userDetailsService;
    private JwtTokenProvider jwtTokenProvider;
    private CustomTenantSuccessHandler handler;
    private User user;
    private Tenant fiche;
    private Tenant alpha;

    @BeforeEach
    void preparer() {
        userRepository = mock(UserRepository.class);
        userDetailsService = mock(CustomUserDetailsService.class);
        jwtTokenProvider = mock(JwtTokenProvider.class);
        when(jwtTokenProvider.generate(any())).thenReturn("jeton");
        handler = new CustomTenantSuccessHandler(userRepository, userDetailsService, jwtTokenProvider,
                mock(AuditLogService.class));
        ReflectionTestUtils.setField(handler, "angularLoginRedirect", "http://angular/login");

        fiche = tenant(1, "schema_fiche");
        alpha = tenant(2, "schema_alpha");
        user = new User();
        ReflectionTestUtils.setField(user, "id", 1L);
        user.setEmail("p@exemple.fr");
        user.setPassword("x");
        user.setActive(true);
        user.setTenant(fiche);
        user.setRoles(Set.of(new Role("ADMIN", "Admin", "ESN")));
        when(userRepository.findByEmail("p@exemple.fr")).thenReturn(Optional.of(user));
    }

    @AfterEach
    void nettoyer() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void enAttente_versLeSelecteur_sansSchemaNiJeton() throws Exception {
        CustomUserDetails enAttente = multi(null);
        when(userDetailsService.loadUserByUsername("p@exemple.fr")).thenReturn(enAttente);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.getSession().setAttribute("CURRENT_TENANT_ID", "ancien_schema");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(req, resp, auth(enAttente));

        assertThat(resp.getRedirectedUrl()).isEqualTo("/choisir-societe");
        assertThat(req.getSession().getAttribute("CURRENT_TENANT_ID")).isNull();
        assertThat(TenantContext.getCurrentTenant()).isNull();
        assertThat(resp.getCookie("_intra_jwt")).isNull();
        verify(jwtTokenProvider, never()).generate(any());
    }

    @Test
    void enAttente_depuisAngular_aucunJetonNiRedirectionAngular() throws Exception {
        CustomUserDetails enAttente = multi(null);
        when(userDetailsService.loadUserByUsername("p@exemple.fr")).thenReturn(enAttente);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie("oauth_origin", "angular"));
        MockHttpServletResponse resp = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(req, resp, auth(enAttente));

        assertThat(resp.getRedirectedUrl()).isEqualTo("/choisir-societe");
        verify(jwtTokenProvider, never()).generate(any());
    }

    @Test
    void enAttente_avecChangementDeMotDePasse_choisitDAbordEtGardeLObligation() throws Exception {
        user.setMustChangePassword(true);
        CustomUserDetails enAttente = multi(null);
        when(userDetailsService.loadUserByUsername("p@exemple.fr")).thenReturn(enAttente);
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse resp = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(req, resp, auth(enAttente));

        assertThat(resp.getRedirectedUrl()).isEqualTo("/choisir-societe");
        assertThat(req.getSession().getAttribute("MUST_CHANGE_PASSWORD")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void principalOAuthHistorique_maisPlusieursSocietes_estRemplaceParLeCompteEnAttente() throws Exception {
        CustomUserDetails enAttente = multi(null);
        when(userDetailsService.loadUserByUsername("p@exemple.fr")).thenReturn(enAttente);
        DefaultOidcUser oidc = new DefaultOidcUser(List.of(), new OidcIdToken("t", null, null,
                Map.of("sub", "1", "email", "p@exemple.fr")));
        CustomOidcUser historique = new CustomOidcUser(oidc, user, "p@exemple.fr");
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse resp = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(req, resp, auth(historique));

        assertThat(resp.getRedirectedUrl()).isEqualTo("/choisir-societe");
        verify(jwtTokenProvider, never()).generate(any());
        SecurityContext ctx = (SecurityContext) req.getSession().getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(ctx).isNotNull();
        assertThat(((CustomUserDetails) ctx.getAuthentication().getPrincipal()).selectionRequise()).isTrue();
    }

    @Test
    void uneSeuleSociete_comportementInchange() throws Exception {
        CustomUserDetails seul = new CustomUserDetails(user);
        when(userDetailsService.loadUserByUsername("p@exemple.fr")).thenReturn(seul);
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse resp = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(req, resp, auth(seul));

        assertThat(resp.getRedirectedUrl()).isEqualTo("/admin/console");
        assertThat(req.getSession().getAttribute("CURRENT_TENANT_ID")).isEqualTo("schema_fiche");
        assertThat(resp.getCookie("_intra_jwt")).isNotNull();
    }

    @Test
    void appartenanceActive_leSchemaEstCeluiDeLaSocieteActive() throws Exception {
        CustomUserDetails actif = multi(10L);
        when(userDetailsService.loadUserByUsername("p@exemple.fr")).thenReturn(multi(null));
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse resp = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(req, resp, auth(actif));

        assertThat(req.getSession().getAttribute("CURRENT_TENANT_ID")).isEqualTo("schema_alpha");
        assertThat(TenantContext.getCurrentTenant()).isEqualTo("schema_alpha");
        // Les droits sont ceux de la societe active (ADMIN dans Alpha), pas ceux d'un rechargement en attente.
        assertThat(resp.getRedirectedUrl()).isEqualTo("/admin/console");
    }

    private CustomUserDetails multi(Long active) {
        return new CustomUserDetails(user, List.of(
                appartenance(10, alpha, new Role("ADMIN", "Admin", "ESN")),
                appartenance(11, tenant(3, "schema_beta"), new Role("CLIENT", "Client", "CLIENT"))), active);
    }

    private static Authentication auth(Object principal) {
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
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
