package com.logicielpourtous.site.features.licence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.ServerRole;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.user.User;

/**
 * Ecran admin {@code /intra/licence/federation} (Maitre uniquement) : rattachement des tenants a un
 * serveur d'accueil et a un partenaire, URL publique des serveurs, serveur par defaut. Meme patron
 * que {@code CompteLicenceAdminControllerTest} (principal reel construit par {@code adminPrincipal}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FederationAdminControllerTest {

    private static final String PAGE = "/intra/licence/federation";

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private LicenceServeurRepository licenceServeurRepository;
    @Autowired private ServeurAccueilService serveurAccueilService;

    @Test
    void get_affichePageAvecTenantsEtServeurs() throws Exception {
        Tenant visible = tenant("Tenant visible " + UUID.randomUUID());
        LicenceServeur serveur = serveur(LicenceServeur.LicenceServeurStatut.ACTIF, "100.64.9.9");

        mockMvc.perform(get(PAGE).with(user(adminPrincipal(tenantRepository))).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(visible.getName())))
                .andExpect(content().string(containsString("100.64.9.9")));
        assertThat(serveur.getId()).isNotNull();
    }

    @Test
    void get_afficheLeServeurRevoqueAuquelUnTenantEstRattache() throws Exception {
        LicenceServeur serveur = serveur(LicenceServeur.LicenceServeurStatut.ACTIF, "100.64.9.9");
        Tenant t = tenant("Tenant sur serveur revoque");
        serveurAccueilService.rattacherTenant(t.getId(), serveur.getId(), null);
        serveur.setStatut(LicenceServeur.LicenceServeurStatut.REVOQUE);
        licenceServeurRepository.saveAndFlush(serveur);

        // L'ecran ne doit pas faire croire que le tenant est rattache au Maitre.
        mockMvc.perform(get(PAGE).with(user(adminPrincipal(tenantRepository))).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("#" + serveur.getId() + " (révoqué)")));
    }

    @Test
    void postTenant_rattacheEtRedirige() throws Exception {
        Tenant partenaire = tenant("Partenaire");
        Tenant client = tenant("Client");
        LicenceServeur serveur = serveur(LicenceServeur.LicenceServeurStatut.ACTIF, "100.64.9.9");

        mockMvc.perform(post(PAGE + "/tenants/{id}", client.getId())
                        .with(user(adminPrincipal(tenantRepository))).with(csrf())
                        .param("serveurLicenceId", String.valueOf(serveur.getId()))
                        .param("tenantPartenaireId", String.valueOf(partenaire.getId())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(PAGE))
                .andExpect(flash().attributeExists("successMessage"));

        Tenant relu = tenantRepository.findById(client.getId()).orElseThrow();
        assertThat(relu.getServeurLicenceId()).isEqualTo(serveur.getId());
        assertThat(relu.getTenantPartenaireId()).isEqualTo(partenaire.getId());
    }

    @Test
    void postTenant_champsVides_retirentLesRattachements() throws Exception {
        Tenant partenaire = tenant("Partenaire");
        Tenant client = tenant("Client");
        serveurAccueilService.rattacherTenant(client.getId(), null, partenaire.getId());

        mockMvc.perform(post(PAGE + "/tenants/{id}", client.getId())
                        .with(user(adminPrincipal(tenantRepository))).with(csrf())
                        .param("serveurLicenceId", "")
                        .param("tenantPartenaireId", ""))
                .andExpect(status().is3xxRedirection());

        Tenant relu = tenantRepository.findById(client.getId()).orElseThrow();
        assertThat(relu.getServeurLicenceId()).isNull();
        assertThat(relu.getTenantPartenaireId()).isNull();
    }

    @Test
    void postTenant_proprePartenaire_afficheUneErreurSansRienModifier() throws Exception {
        Tenant t = tenant("Seul");

        mockMvc.perform(post(PAGE + "/tenants/{id}", t.getId())
                        .with(user(adminPrincipal(tenantRepository))).with(csrf())
                        .param("tenantPartenaireId", String.valueOf(t.getId())))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("errorMessage"));

        assertThat(tenantRepository.findById(t.getId()).orElseThrow().getTenantPartenaireId()).isNull();
    }

    @Test
    void postUrl_valide_enregistreNormalisee() throws Exception {
        LicenceServeur serveur = serveur(LicenceServeur.LicenceServeurStatut.ACTIF, "100.64.9.9");

        mockMvc.perform(post(PAGE + "/serveurs/{id}/url", serveur.getId())
                        .with(user(adminPrincipal(tenantRepository))).with(csrf())
                        .param("urlPublique", "https://app.exemple.com/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("successMessage"));

        assertThat(licenceServeurRepository.findById(serveur.getId()).orElseThrow().getUrlPublique())
                .isEqualTo("https://app.exemple.com");
    }

    @Test
    void postUrl_http_refusee_sansRienModifier() throws Exception {
        LicenceServeur serveur = serveur(LicenceServeur.LicenceServeurStatut.ACTIF, "100.64.9.9");

        mockMvc.perform(post(PAGE + "/serveurs/{id}/url", serveur.getId())
                        .with(user(adminPrincipal(tenantRepository))).with(csrf())
                        .param("urlPublique", "http://app.exemple.com"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("errorMessage"));

        assertThat(licenceServeurRepository.findById(serveur.getId()).orElseThrow().getUrlPublique()).isNull();
    }

    @Test
    void postUrl_serveurInconnu_afficheUneErreurSansPlanter() throws Exception {
        mockMvc.perform(post(PAGE + "/serveurs/{id}/url", 987654321L)
                        .with(user(adminPrincipal(tenantRepository))).with(csrf())
                        .param("urlPublique", "https://app.exemple.com"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("errorMessage"));
    }

    @Test
    void postDefaut_definitLeServeurParDefautEtRefuseUnServeurRevoque() throws Exception {
        LicenceServeur actif = serveur(LicenceServeur.LicenceServeurStatut.ACTIF, "100.64.9.9");
        LicenceServeur revoque = serveur(LicenceServeur.LicenceServeurStatut.REVOQUE, "100.64.9.8");

        mockMvc.perform(post(PAGE + "/defaut")
                        .with(user(adminPrincipal(tenantRepository))).with(csrf())
                        .param("serveurId", String.valueOf(actif.getId())))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("successMessage"));
        assertThat(serveurAccueilService.serveurParDefautId()).contains(actif.getId());

        mockMvc.perform(post(PAGE + "/defaut")
                        .with(user(adminPrincipal(tenantRepository))).with(csrf())
                        .param("serveurId", String.valueOf(revoque.getId())))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("errorMessage"));
        assertThat(serveurAccueilService.serveurParDefautId()).contains(actif.getId());
    }

    @Test
    void utilisateurNonAdmin_estRefuse() throws Exception {
        mockMvc.perform(get(PAGE).with(user(principal(tenantRepository, "USER"))))
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
            mockMvc.perform(get(PAGE).with(user(adminPrincipal(tenantRepository))).with(csrf()))
                    .andExpect(status().isNotFound());
        }
    }

    private Tenant tenant(String nom) {
        Tenant t = new Tenant();
        t.setName(nom);
        t.setSchemaName("t_fed_" + UUID.randomUUID().toString().replace("-", ""));
        return tenantRepository.saveAndFlush(t);
    }

    private LicenceServeur serveur(LicenceServeur.LicenceServeurStatut statut, String adresse) {
        LicenceServeur s = new LicenceServeur();
        s.setRole(ServerRole.PARTNER);
        s.setIdentifiantInstallationStable(UUID.randomUUID().toString());
        s.setAdresseDeclaree(adresse);
        s.setStatut(statut);
        return licenceServeurRepository.saveAndFlush(s);
    }

    private static CustomUserDetails adminPrincipal(TenantRepository tenantRepository) {
        return principal(tenantRepository, "ADMIN");
    }

    private static CustomUserDetails principal(TenantRepository tenantRepository, String role) {
        Tenant tenant = new Tenant();
        tenant.setName("Test Tenant");
        tenant.setSchemaName("test_tenant_" + UUID.randomUUID());
        tenant = tenantRepository.save(tenant);

        User user = new User();
        user.setEmail("fed-test-" + UUID.randomUUID() + "@logicielpourtous.com");
        user.setPassword("test");
        user.setFullName("Test " + role);
        user.setActive(true);
        user.setTenant(tenant);
        user.setRoles(Set.of(new Role(role, role, "ESN")));

        return new CustomUserDetails(user);
    }
}
