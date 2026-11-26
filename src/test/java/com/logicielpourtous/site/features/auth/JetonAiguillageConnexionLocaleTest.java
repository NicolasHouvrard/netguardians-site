package com.logicielpourtous.site.features.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import com.logicielpourtous.site.config.JwtTokenProvider;
import com.logicielpourtous.site.features.audit.AuditLogService;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantContext;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;

/** Le jeton d'identite de l'aiguillage ne survit pas dans une session ou une connexion locale reussit. */
class JetonAiguillageConnexionLocaleTest {

    @AfterEach
    void nettoyer() {
        TenantContext.clear();
    }

    @Test
    void le_jeton_est_retire_apres_une_connexion_locale_reussie() throws Exception {
        UserRepository users = mock(UserRepository.class);
        CustomUserDetailsService details = mock(CustomUserDetailsService.class);
        JwtTokenProvider jwt = mock(JwtTokenProvider.class);
        when(jwt.generate(any())).thenReturn("jeton");
        when(jwt.jetonDuCompte(any())).thenCallRealMethod();
        CustomTenantSuccessHandler handler = new CustomTenantSuccessHandler(users, details, jwt, mock(AuditLogService.class));
        ReflectionTestUtils.setField(handler, "angularLoginRedirect", "http://angular/login");

        Tenant fiche = new Tenant();
        ReflectionTestUtils.setField(fiche, "id", 1L);
        fiche.setSchemaName("schema_fiche");
        User user = new User();
        ReflectionTestUtils.setField(user, "id", 1L);
        user.setEmail("p@exemple.fr");
        user.setPassword("x");
        user.setActive(true);
        user.setTenant(fiche);
        user.setRoles(Set.of(new Role("ADMIN", "Admin", "ESN")));
        when(users.findByEmail("p@exemple.fr")).thenReturn(Optional.of(user));

        MockHttpServletRequest requete = new MockHttpServletRequest();
        requete.getSession().setAttribute(AiguillageRequis.ATTRIBUT_JETON, "jeton-fictif-pour-test");
        requete.getSession().setAttribute(AiguillageRequis.ATTRIBUT_JETON_INSTANT, System.currentTimeMillis());
        requete.getSession().setAttribute(AiguillageRequis.ATTRIBUT_LISTE, java.util.List.of());

        try {
            handler.onAuthenticationSuccess(requete, new MockHttpServletResponse(),
                    new UsernamePasswordAuthenticationToken("p@exemple.fr", "x"));
        } catch (RuntimeException e) {
            // La suite de la redirection n'est pas l'objet de ce test : seul l'etat de session compte.
        }

        assertThat(requete.getSession().getAttribute(AiguillageRequis.ATTRIBUT_JETON)).isNull();
        assertThat(requete.getSession().getAttribute(AiguillageRequis.ATTRIBUT_JETON_INSTANT)).isNull();
        assertThat(requete.getSession().getAttribute(AiguillageRequis.ATTRIBUT_LISTE)).isNull();
    }
}
