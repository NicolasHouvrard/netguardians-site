package com.logicielpourtous.site.features.licence;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.user.User;

/**
 * Page d'administration {@code /intra/societes/doublons} (Maitre uniquement) : affiche le rapport des
 * societes qui partagent un identifiant, sans rien corriger.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DoublonsSocietesControllerTest {

    private static final String PAGE = "/intra/societes/doublons";

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private JdbcTemplate jdbc;

    private void client(String nom, String siren) {
        jdbc.update("INSERT INTO customers (active, companyname, siren) VALUES (true, ?, ?)", nom, siren);
    }

    @Test
    void get_afficheLesGroupesDeDoublons() throws Exception {
        client("Societe Alpha Test", "999000777");
        client("Societe Beta Test", "999000777");

        mockMvc.perform(get(PAGE).with(user(principal("ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("999000777")))
                .andExpect(content().string(containsString("Societe Alpha Test")))
                .andExpect(content().string(containsString("Societe Beta Test")))
                .andExpect(content().string(containsString("Aucune fusion automatique")))
                .andExpect(content().string(containsString("peut aussi apparaître")));
    }

    @Test
    void get_sansDoublon_afficheUnMessageVide() throws Exception {
        client("Societe Seule Test", "999000888");

        mockMvc.perform(get(PAGE).with(user(principal("ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Aucun doublon")));
    }

    @Test
    void utilisateurNonAdmin_estRefuse() throws Exception {
        mockMvc.perform(get(PAGE).with(user(principal("USER"))))
                .andExpect(status().isForbidden());
    }

    @Nested
    @TestPropertySource(properties = "app.role=partner")
    @Transactional
    class NotMasterTest {

        @Autowired private MockMvc mockMvc;
        @Autowired private TenantRepository tenantRepository;

        @Test
        void pageIntrouvableSiCeServeurNEstPasLeMaitre() throws Exception {
            mockMvc.perform(get(PAGE).with(user(principal(tenantRepository, "ADMIN"))))
                    .andExpect(status().isNotFound());
        }
    }

    private CustomUserDetails principal(String role) {
        return principal(tenantRepository, role);
    }

    private static CustomUserDetails principal(TenantRepository tenantRepository, String role) {
        Tenant tenant = new Tenant();
        tenant.setName("Test Tenant");
        tenant.setSchemaName("test_tenant_" + UUID.randomUUID());
        tenant = tenantRepository.save(tenant);

        User user = new User();
        user.setEmail("doublons-test-" + UUID.randomUUID() + "@logicielpourtous.com");
        user.setPassword("test");
        user.setFullName("Test " + role);
        user.setActive(true);
        user.setTenant(tenant);
        user.setRoles(Set.of(new Role(role, role, "ESN")));
        return new CustomUserDetails(user);
    }
}
