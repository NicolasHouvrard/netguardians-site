package com.logicielpourtous.site.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

/**
 * Protection CSRF CIBLEE de la chaine web (session par cookie) : seuls les ecrans sensibles de
 * licence, de gestion des ESN/tenants et de reglage de la societe l'exigent pour l'instant ; le reste
 * de l'interface web est volontairement inchange (extension par lots prevue). Les chaines d'API
 * (cle API / jeton, sans session) n'ont pas de CSRF : hors sujet ici.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CsrfCibleTest {

    private static final Pattern FORMULAIRE_POST = Pattern.compile("<form\\b[^>]*method=\"post\"[^>]*>", Pattern.CASE_INSENSITIVE);

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenantRepository;

    @Test
    void postLicenceSansJeton_estRefuse() throws Exception {
        mockMvc.perform(post("/intra/licence/federation/defaut").with(user(admin())))
                .andExpect(status().isForbidden());
    }

    @Test
    void postLicenceAvecJeton_estAccepte() throws Exception {
        mockMvc.perform(post("/intra/licence/federation/defaut").with(user(admin())).with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void postGestionDesEsnSansJeton_estRefuse() throws Exception {
        mockMvc.perform(post("/intra/esns").with(user(admin())).param("companyName", "X"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/intra/esns/1/toggle").with(user(admin())))
                .andExpect(status().isForbidden());
    }

    @Test
    void postReglageSocieteSansJeton_estRefuse() throws Exception {
        mockMvc.perform(post("/intra/settings/company").with(user(admin())))
                .andExpect(status().isForbidden());
    }

    @Test
    void postRepriseDuRegistreSansJeton_estRefuse() throws Exception {
        mockMvc.perform(post("/intra/societes/reprise").with(user(admin())))
                .andExpect(status().isForbidden());
    }

    @Test
    void getRestePossibleSansJeton() throws Exception {
        mockMvc.perform(get("/intra/licence/federation").with(user(admin())))
                .andExpect(status().isOk());
    }

    @Test
    void pagesCibleesDonnentUnJetonAChaqueFormulairePost() throws Exception {
        for (String page : new String[] {"/intra/licence/federation", "/intra/licence/comptes", "/intra/esns",
                "/intra/societes/reprise"}) {
            String html = mockMvc.perform(get(page).with(user(admin())))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            int formulaires = compter(FORMULAIRE_POST, html);
            int jetons = compter(Pattern.compile("name=\"_csrf\""), html);
            assertThat(formulaires).as("formulaires POST de " + page).isGreaterThan(0);
            assertThat(jetons).as("jetons _csrf de " + page + " pour " + formulaires + " formulaire(s)")
                    .isGreaterThanOrEqualTo(formulaires);
        }
    }

    @Test
    void ecranNonCible_resteAccessibleSansJetonPourLInstant() throws Exception {
        // Documente le perimetre : le reste de l'interface web n'est pas encore protege.
        int statut = mockMvc.perform(post("/intra/settings/site").with(user(admin())))
                .andReturn().getResponse().getStatus();
        assertThat(statut).isNotEqualTo(403);
    }

    private static int compter(Pattern motif, String texte) {
        Matcher m = motif.matcher(texte);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private CustomUserDetails admin() {
        Tenant tenant = new Tenant();
        tenant.setName("Test Tenant");
        tenant.setSchemaName("test_tenant_" + UUID.randomUUID());
        tenant = tenantRepository.save(tenant);

        User user = new User();
        user.setEmail("csrf-test-" + UUID.randomUUID() + "@logicielpourtous.com");
        user.setPassword("test");
        user.setFullName("Test Admin");
        user.setActive(true);
        user.setTenant(tenant);
        user.setRoles(Set.of(new Role("ADMIN", "Admin", "ESN")));
        return new CustomUserDetails(user);
    }
}
