package com.logicielpourtous.site.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

/**
 * Socle du front HTMX sur la vraie chaine de securite web : session expiree pendant un appel HTMX,
 * CSRF des ecrans du socle, controle d'acces. Les lignes creees le sont dans une transaction annulee.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SocleSecuriteWebTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenantRepository;

    @Test
    void sessionExpireeSurAppelHtmx_repond200AvecHxRedirect() throws Exception {
        mockMvc.perform(get("/admin/socle").header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Redirect", "/login"))
                .andReturn();
        String corps = mockMvc.perform(get("/admin/socle").header("HX-Request", "true"))
                .andReturn().getResponse().getContentAsString();
        assertThat(corps).isEmpty();
    }

    @Test
    void sessionExpireeSansHtmx_redirigeVersLogin() throws Exception {
        mockMvc.perform(get("/admin/socle"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/login")));
    }

    @Test
    void postSocleSansJeton_estRefuse() throws Exception {
        mockMvc.perform(post("/admin/socle/liste").with(user(compte("ADMIN"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void postSocleAvecJeton_estAccepte() throws Exception {
        int statut = mockMvc.perform(post("/admin/socle/liste").with(user(compte("ADMIN"))).with(csrf()))
                .andReturn().getResponse().getStatus();
        assertThat(statut).isNotEqualTo(403);
        assertThat(statut).isEqualTo(200);
    }

    @Test
    void compteNonAdmin_recoit403SurLeSocle() throws Exception {
        mockMvc.perform(get("/admin/socle").with(user(compte("USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void scriptDuSocleAccessibleAnonymement_etLayoutPorteLesBalisesCsrf() throws Exception {
        mockMvc.perform(get("/js/socle.js")).andExpect(status().isOk());
        String html = mockMvc.perform(get("/admin/socle").with(user(compte("ADMIN"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("<meta name=\"_csrf\"").contains("<meta name=\"_csrf_header\"")
                .contains("id=\"toasts\"").contains("/js/socle.js");
    }

    @Test
    void postHtmxAnonymeSansJeton_sessionExpiree_repond200AvecHxRedirect() throws Exception {
        mockMvc.perform(post("/admin/socle/liste").header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Redirect", "/login"));
        String corps = mockMvc.perform(post("/admin/socle/liste").header("HX-Request", "true"))
                .andReturn().getResponse().getContentAsString();
        assertThat(corps).isEmpty();
    }

    @Test
    void postHtmxAdminAuthentifieSansJeton_resteRefuse() throws Exception {
        mockMvc.perform(post("/admin/socle/liste").header("HX-Request", "true").with(user(compte("ADMIN"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void postHtmxNonAdminAvecJeton_resteRefuse() throws Exception {
        mockMvc.perform(post("/admin/socle/liste").header("HX-Request", "true").with(user(compte("USER"))).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void scriptDuSocle_necommuniquePasLeJetonAUneAutreOrigine() throws Exception {
        String js = mockMvc.perform(get("/js/socle.js")).andReturn().getResponse().getContentAsString();
        assertThat(js).contains("location.origin");
    }

    @Test
    void pagesDePlugin_anonyme_redirigeVersLogin() throws Exception {
        mockMvc.perform(get("/p/x/y"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/login")));
        // Meme un chemin a un segment (que la regle /{slug} ouvre) : /p seul n'est pas une page publique.
        mockMvc.perform(get("/p/x"))
                .andExpect(status().isFound());
    }

    @Test
    void pagesDePlugin_htmxAnonyme_repond200AvecHxRedirect() throws Exception {
        mockMvc.perform(get("/p/x/y").header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Redirect", "/login"));
        mockMvc.perform(post("/p/x/y").header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Redirect", "/login"));
    }

    @Test
    void pagesDePlugin_postAuthentifieSansJeton_estRefuse_pourToutesLesMethodesModifiantes() throws Exception {
        CustomUserDetails compte = compte("USER");
        for (org.springframework.http.HttpMethod m : new org.springframework.http.HttpMethod[] {
                org.springframework.http.HttpMethod.POST, org.springframework.http.HttpMethod.PUT,
                org.springframework.http.HttpMethod.PATCH, org.springframework.http.HttpMethod.DELETE }) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .request(m, "/p/x/y").with(user(compte)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void pagesDePlugin_postAuthentifieAvecJeton_neSeFaitPasRefuserParLeCsrf() throws Exception {
        int statut = mockMvc.perform(post("/p/x/y").with(user(compte("USER"))).with(csrf()))
                .andReturn().getResponse().getStatus();
        assertThat(statut).isNotEqualTo(403);
        assertThat(statut).isEqualTo(404);
    }

    @Test
    void apiPlugins_resteSurLaChaineJwt_sansCsrfEtSans401Anonyme() throws Exception {
        mockMvc.perform(post("/api/plugins/x/y")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/plugins/x/y")).andExpect(status().isUnauthorized());
    }

    private CustomUserDetails compte(String role) {
        Tenant tenant = new Tenant();
        tenant.setName("Test Tenant");
        tenant.setSchemaName("test_tenant_" + UUID.randomUUID());
        tenant = tenantRepository.save(tenant);

        User user = new User();
        user.setEmail("socle-test-" + UUID.randomUUID() + "@logicielpourtous.com");
        user.setPassword("test");
        user.setFullName("Test " + role);
        user.setActive(true);
        user.setTenant(tenant);
        user.setRoles(Set.of(new Role(role, role, "ESN")));
        return new CustomUserDetails(user);
    }
}
