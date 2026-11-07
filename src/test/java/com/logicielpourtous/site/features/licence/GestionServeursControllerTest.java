package com.logicielpourtous.site.features.licence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.ServerRole;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.request.Request;
import com.logicielpourtous.site.features.request.RequestPriority;
import com.logicielpourtous.site.features.request.RequestRepository;
import com.logicielpourtous.site.features.request.RequestStatus;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.sync.RequestSyncPayload;
import com.logicielpourtous.site.features.sync.SecondaryServer;
import com.logicielpourtous.site.features.sync.SecondaryServerRepository;
import com.logicielpourtous.site.features.sync.SyncConflict;
import com.logicielpourtous.site.features.sync.SyncConflictRepository;
import com.logicielpourtous.site.features.user.User;

/**
 * Ecran I-4 {@code /intra/licence/serveurs} + {@code /intra/licence/conflits} : gestion des
 * {@code SecondaryServer}/{@code SyncConflict} (module {@code sync}, déjà livré), aujourd'hui
 * gérables seulement via SQL manuel, et generation de jeton d'installation cote PARTNER.
 *
 * <p>Classe de test externe au role MASTER par defaut (aucun {@code app.role} configure dans les
 * proprietes de test — voir {@code ServerRoleProperties}) : les 2 ecrans principaux sont donc
 * testes ici SANS garde de role (coherent avec le fait qu'ils sont disponibles quel que soit le
 * role). Le role PARTNER (bouton de generation de jeton) est teste dans {@link PartnerRoleTest},
 * avec un {@link LicenceClient} de test (pas de vrai appel HTTP sortant) injecte via {@code
 * @TestConfiguration} — meme patron d'authentification admin que {@code CompteLicenceAdminControllerTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class GestionServeursControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private SecondaryServerRepository secondaryServerRepository;
    @Autowired private SyncConflictRepository syncConflictRepository;
    @Autowired private RequestRepository requestRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private ObjectMapper objectMapper;

    // ── Step 1 : écran serveurs ────────────────────────────────────────────────

    @Test
    void listServeurs_showsStatutAndCustomerId() throws Exception {
        Customer customer = customer("Client serveur liste");
        SecondaryServer serveur = secondaryServer("Secondaire liste " + UUID.randomUUID(), ServerRole.CLIENT, customer.getId());

        mockMvc.perform(get("/intra/licence/serveurs").with(user(adminPrincipal(tenantRepository))).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(serveur.getNom())))
                .andExpect(content().string(containsString("ACTIF")))
                .andExpect(content().string(containsString(String.valueOf(customer.getId()))));
    }

    @Test
    void revokeServeur_setsStatutRevoque() throws Exception {
        SecondaryServer serveur = secondaryServer("Secondaire a revoquer " + UUID.randomUUID(), ServerRole.PARTNER, null);

        mockMvc.perform(post("/intra/licence/serveurs/{id}/revoquer", serveur.getId())
                        .with(user(adminPrincipal(tenantRepository))).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(secondaryServerRepository.findById(serveur.getId()).orElseThrow().getStatut())
                .isEqualTo(SecondaryServer.SecondaryServerStatut.REVOQUE);
    }

    @Test
    void generateManualCode_createsUsableEnrollmentCode() throws Exception {
        String nom = "Nouveau secondaire " + UUID.randomUUID();

        mockMvc.perform(post("/intra/licence/serveurs")
                        .with(user(adminPrincipal(tenantRepository))).with(csrf())
                        .param("nom", nom)
                        .param("role", "CLIENT"))
                .andExpect(status().is3xxRedirection());

        SecondaryServer cree = secondaryServerRepository.findAll().stream()
                .filter(s -> nom.equals(s.getNom()))
                .findFirst().orElseThrow();
        assertThat(cree.getRole()).isEqualTo(ServerRole.CLIENT);
        assertThat(cree.getStatut()).isEqualTo(SecondaryServer.SecondaryServerStatut.ACTIF);
        assertThat(cree.getEnrollmentCode()).isNotBlank();
        assertThat(cree.getEnrollmentCodeExpiresAt()).isAfter(Instant.now());

        // Le code genere est consomme par le vrai POST /api/sync/enroll deja existant.
        mockMvc.perform(post("/api/sync/enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enrollmentCode\":\"" + cree.getEnrollmentCode() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKey").exists());

        assertThat(secondaryServerRepository.findById(cree.getId()).orElseThrow().getEnrollmentCode()).isNull();
    }

    @Test
    void createServeur_invalidRole_isRejectedWithoutCreatingARow() throws Exception {
        String nom = "Role invalide " + UUID.randomUUID();

        mockMvc.perform(post("/intra/licence/serveurs")
                        .with(user(adminPrincipal(tenantRepository))).with(csrf())
                        .param("nom", nom)
                        .param("role", "MASTER"))
                .andExpect(status().is3xxRedirection());

        assertThat(secondaryServerRepository.findAll().stream().noneMatch(s -> nom.equals(s.getNom()))).isTrue();
    }

    @Test
    void createServeur_unknownCustomerId_isRejectedWithoutCreatingARow() throws Exception {
        String nom = "Client inconnu " + UUID.randomUUID();

        mockMvc.perform(post("/intra/licence/serveurs")
                        .with(user(adminPrincipal(tenantRepository))).with(csrf())
                        .param("nom", nom)
                        .param("role", "CLIENT")
                        .param("customerId", "999999999"))
                .andExpect(status().is3xxRedirection());

        assertThat(secondaryServerRepository.findAll().stream().noneMatch(s -> nom.equals(s.getNom()))).isTrue();
    }

    // ── Step 3 : écran conflits ────────────────────────────────────────────────

    @Test
    void listConflits_showsExpectedAndReceivedVersion() throws Exception {
        Customer customer = customer("Client conflit liste");
        Request request = request(customer, "Original");
        SyncConflict conflit = creerConflit(request, "Modifie", 42L, 7L);

        mockMvc.perform(get("/intra/licence/conflits").with(user(adminPrincipal(tenantRepository))).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(conflit.getEntitySyncId().toString())))
                .andExpect(content().string(containsString("42")))
                .andExpect(content().string(containsString("7")));
    }

    @Test
    void resolveConflit_applyingReceivedPayload_callsRequestSyncApplierAndMarksConflitResolved() throws Exception {
        Customer customer = customer("Client conflit appliquer");
        Request request = request(customer, "Original");
        SyncConflict conflit = creerConflit(request, "Titre recu du conflit", request.getVersion(), request.getVersion() + 1);

        mockMvc.perform(post("/intra/licence/conflits/{id}/appliquer", conflit.getId())
                        .with(user(adminPrincipal(tenantRepository))).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(requestRepository.findById(request.getId()).orElseThrow().getTitle())
                .isEqualTo("Titre recu du conflit");
        assertThat(syncConflictRepository.findById(conflit.getId()).orElseThrow().isResolu()).isTrue();
        assertThat(syncConflictRepository.findByResolu(false)).noneMatch(c -> c.getId().equals(conflit.getId()));
    }

    @Test
    void resolveConflit_keepingCurrentState_justMarksConflitResolvedWithoutChangingTheRequest() throws Exception {
        Customer customer = customer("Client conflit garder");
        Request request = request(customer, "Original intact");
        SyncConflict conflit = creerConflit(request, "Titre recu ignore", request.getVersion(), request.getVersion() + 1);

        mockMvc.perform(post("/intra/licence/conflits/{id}/garder", conflit.getId())
                        .with(user(adminPrincipal(tenantRepository))).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(requestRepository.findById(request.getId()).orElseThrow().getTitle()).isEqualTo("Original intact");
        assertThat(syncConflictRepository.findById(conflit.getId()).orElseThrow().isResolu()).isTrue();
    }

    @Test
    void resolveConflit_alreadyResolved_isNoOp() throws Exception {
        Customer customer = customer("Client conflit deja tranche");
        Request request = request(customer, "Original");
        SyncConflict conflit = creerConflit(request, "Titre recu", request.getVersion(), request.getVersion() + 1);
        conflit.setResolu(true);
        syncConflictRepository.save(conflit);

        mockMvc.perform(post("/intra/licence/conflits/{id}/appliquer", conflit.getId())
                        .with(user(adminPrincipal(tenantRepository))).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(requestRepository.findById(request.getId()).orElseThrow().getTitle()).isEqualTo("Original");
    }

    // ── Step 5 : bouton PARTNER masqué quand le rôle n'est pas PARTNER (rôle par défaut = MASTER) ──

    @Test
    void generateJetonButton_notShownWhenRoleIsNotPartner() throws Exception {
        mockMvc.perform(get("/intra/licence/serveurs").with(user(adminPrincipal(tenantRepository))).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Générer un jeton d'installation"))));
    }

    @Test
    void generateJeton_notPartnerRole_isNotFound() throws Exception {
        mockMvc.perform(post("/intra/licence/serveurs/jetons")
                        .with(user(adminPrincipal(tenantRepository))).with(csrf())
                        .param("customerId", "1"))
                .andExpect(status().isNotFound());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Customer customer(String nom) {
        Customer c = new Customer();
        c.setCompanyName(nom + " " + UUID.randomUUID());
        return customerRepository.save(c);
    }

    private Request request(Customer owner, String title) {
        Request r = new Request();
        r.setCustomer(owner);
        r.setTitle(title);
        return requestRepository.save(r);
    }

    private SecondaryServer secondaryServer(String nom, ServerRole role, Long customerId) {
        SecondaryServer s = new SecondaryServer();
        s.setNom(nom);
        s.setRole(role);
        s.setCustomerId(customerId);
        return secondaryServerRepository.save(s);
    }

    private SyncConflict creerConflit(Request request, String titreRecu, long versionAttendue, long versionRecue) {
        RequestSyncPayload payload = new RequestSyncPayload(request.getSyncId(), versionRecue, RequestStatus.IN_PROGRESS,
                null, RequestPriority.HIGH, titreRecu, "desc", null, null, List.of());
        SyncConflict conflit = new SyncConflict();
        conflit.setEntitySyncId(request.getSyncId());
        conflit.setVersionAttendue(versionAttendue);
        conflit.setVersionRecue(versionRecue);
        conflit.setPayloadRecu(ecrireJson(payload));
        conflit.setResolu(false);
        return syncConflictRepository.save(conflit);
    }

    private String ecrireJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static CustomUserDetails adminPrincipal(TenantRepository tenantRepository) {
        Tenant tenant = new Tenant();
        tenant.setName("Test Tenant");
        tenant.setSchemaName("test_tenant_" + UUID.randomUUID());
        tenant = tenantRepository.save(tenant);

        User user = new User();
        user.setEmail("admin-test-" + UUID.randomUUID() + "@logicielpourtous.com");
        user.setPassword("test");
        user.setFullName("Test Admin");
        user.setActive(true);
        user.setTenant(tenant);
        user.setRoles(Set.of(new Role("ADMIN", "Admin", "ESN")));

        return new CustomUserDetails(user);
    }

    // ── Step 5 : rôle PARTNER — génération de jeton (LicenceClient de test, pas de vrai HTTP) ──

    @Nested
    @TestPropertySource(properties = "app.role=partner")
    @Transactional
    class PartnerRoleTest {

        @Autowired private MockMvc mockMvc;
        @Autowired private TenantRepository tenantRepository;
        @Autowired private CustomerRepository customerRepository;

        @Test
        void generateJetonForCustomer_onPartnerInstance_callsMpAndDisplaysToken() throws Exception {
            Customer customer = new Customer();
            customer.setCompanyName("Client jeton " + UUID.randomUUID());
            customer = customerRepository.save(customer);

            MvcResult result = mockMvc.perform(post("/intra/licence/serveurs/jetons")
                            .with(user(adminPrincipal(tenantRepository))).with(csrf())
                            .param("customerId", String.valueOf(customer.getId())))
                    .andExpect(status().is3xxRedirection())
                    .andReturn();

            Object flash = result.getFlashMap().get("successMessage");
            assertThat((String) flash).contains(FakeLicenceClientConfig.JETON_RENVOYE);
        }

        @Test
        void generateJetonButton_shownOnPartnerInstance() throws Exception {
            mockMvc.perform(get("/intra/licence/serveurs").with(user(adminPrincipal(tenantRepository))).with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Générer un jeton d'installation")));
        }

        // Round de revue de securite (brief) : un customerId invalide/perime ne doit PAS declencher
        // d'appel au maitre.
        @Test
        void generateJeton_unknownCustomer_doesNotCallLicenceClient() throws Exception {
            int avant = FakeLicenceClientConfig.APPELS.get();

            mockMvc.perform(post("/intra/licence/serveurs/jetons")
                            .with(user(adminPrincipal(tenantRepository))).with(csrf())
                            .param("customerId", "999999999"))
                    .andExpect(status().is3xxRedirection());

            assertThat(FakeLicenceClientConfig.APPELS.get()).isEqualTo(avant);
        }

        @TestConfiguration
        static class FakeLicenceClientConfig {
            static final String JETON_RENVOYE = "jeton-de-test-simule";
            static final AtomicInteger APPELS = new AtomicInteger();

            @Bean
            LicenceClient licenceClient(EtatLicenceLocaleRepository etatLicenceLocaleRepository, ObjectMapper objectMapper) {
                return new LicenceClient("http://maitre-test-non-appele", etatLicenceLocaleRepository, objectMapper) {
                    @Override
                    public JetonGenere genererJeton(Long customerId) {
                        APPELS.incrementAndGet();
                        return new JetonGenere(JETON_RENVOYE, Instant.now().plusSeconds(3600));
                    }
                };
            }
        }
    }
}
