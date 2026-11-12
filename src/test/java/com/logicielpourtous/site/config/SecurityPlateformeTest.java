package com.logicielpourtous.site.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.user.User;

/** Securite des API de la plateforme (plateforme inactive : seul le filtrage d'acces est observe). */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SecurityPlateformeTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenantRepository;

    @Test
    void plateforme_sans_jeton_refusee_401() throws Exception {
        mockMvc.perform(get("/api/plateforme/plugins")).andExpect(status().isUnauthorized());
    }

    @Test
    void plateforme_avec_role_esn_user_refusee_403() throws Exception {
        mockMvc.perform(get("/api/plateforme/plugins").with(user(compte("COMMERCIAL"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void plateforme_admin_acceptee() throws Exception {
        // Le controleur d'administration n'existe qu'en tache 8 : on verifie seulement que la securite laisse passer.
        int statut = mockMvc.perform(get("/api/plateforme/plugins").with(user(compte("ADMIN"))))
                .andReturn().getResponse().getStatus();
        assertThat(statut).isNotIn(401, 403);
    }

    @Test
    void plugins_sans_jeton_refuses_401() throws Exception {
        mockMvc.perform(get("/api/plugins/testnotes/notes")).andExpect(status().isUnauthorized());
    }

    @Test
    void plugins_authentifie_n_est_pas_refuse_par_la_securite() throws Exception {
        int statut = mockMvc.perform(get("/api/plugins/testnotes/notes").with(user(compte("COMMERCIAL"))))
                .andReturn().getResponse().getStatus();
        assertThat(statut).isNotIn(401, 403);
    }

    private CustomUserDetails compte(String role) {
        Tenant tenant = new Tenant();
        tenant.setName("Test Tenant");
        tenant.setSchemaName("test_tenant_" + UUID.randomUUID());
        tenant = tenantRepository.save(tenant);

        User u = new User();
        u.setEmail("plateforme-test-" + UUID.randomUUID() + "@logicielpourtous.com");
        u.setPassword("test");
        u.setFullName("Test " + role);
        u.setActive(true);
        u.setTenant(tenant);
        u.setRoles(Set.of(new Role(role, role, "ESN")));
        return new CustomUserDetails(u);
    }
}
