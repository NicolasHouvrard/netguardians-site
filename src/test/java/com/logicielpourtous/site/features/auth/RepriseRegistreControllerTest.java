package com.logicielpourtous.site.features.auth;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.logicielpourtous.site.features.auth.RepriseRegistreService.BilanReprise;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.user.User;

/**
 * Ecran {@code /intra/societes/reprise} : Maitre uniquement, GET = simulation, POST (CSRF) = execution.
 * Le service est simule : la reprise reelle est couverte par {@link RepriseRegistreServiceTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RepriseRegistreControllerTest {

    private static final String PAGE = "/intra/societes/reprise";

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenantRepository;
    @MockitoBean private RepriseRegistreService service;

    @Test
    void get_afficheLaSimulationEtEchappeLesValeurs() throws Exception {
        when(service.reprendre(true)).thenReturn(new BilanReprise(3, 1, 1,
                List.of("customers #7 (<script>alert(1)</script>) : ignorée")));

        mockMvc.perform(get(PAGE).with(user(principal(tenantRepository, "ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Simulation")))
                .andExpect(content().string(containsString("&lt;script&gt;alert(1)")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
        verify(service, never()).reprendre(false);
    }

    @Test
    void post_sansJetonCsrf_estRefuse() throws Exception {
        mockMvc.perform(post(PAGE).with(user(principal(tenantRepository, "ADMIN"))))
                .andExpect(status().isForbidden());
        verify(service, never()).reprendre(anyBoolean());
    }

    @Test
    void post_avecJeton_executeLaReprise() throws Exception {
        when(service.reprendre(false)).thenReturn(new BilanReprise(2, 0, 0, List.of()));

        mockMvc.perform(post(PAGE).with(user(principal(tenantRepository, "ADMIN"))).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Reprise exécutée")));
        verify(service).reprendre(false);
    }

    @Test
    void utilisateurNonAdmin_estRefuse() throws Exception {
        mockMvc.perform(get(PAGE).with(user(principal(tenantRepository, "USER"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(PAGE).with(user(principal(tenantRepository, "USER"))).with(csrf()))
                .andExpect(status().isForbidden());
        verify(service, never()).reprendre(anyBoolean());
    }

    @Nested
    @TestPropertySource(properties = "app.role=partner")
    @Transactional
    class NotMasterTest {

        @Autowired private MockMvc mockMvc;
        @Autowired private TenantRepository tenantRepository;

        @Test
        void ceServeurNEstPasLeMaitre_404_GetEtPost() throws Exception {
            mockMvc.perform(get(PAGE).with(user(principal(tenantRepository, "ADMIN"))))
                    .andExpect(status().isNotFound());
            mockMvc.perform(post(PAGE).with(user(principal(tenantRepository, "ADMIN"))).with(csrf()))
                    .andExpect(status().isNotFound());
        }
    }

    private static CustomUserDetails principal(TenantRepository tenantRepository, String role) {
        Tenant tenant = new Tenant();
        tenant.setName("Test Tenant");
        tenant.setSchemaName("test_tenant_" + UUID.randomUUID());
        tenant = tenantRepository.save(tenant);

        User user = new User();
        user.setEmail("reprise-test-" + UUID.randomUUID() + "@logicielpourtous.com");
        user.setPassword("test");
        user.setFullName("Test " + role);
        user.setActive(true);
        user.setTenant(tenant);
        user.setRoles(Set.of(new Role(role, role, "ESN")));
        return new CustomUserDetails(user);
    }
}
