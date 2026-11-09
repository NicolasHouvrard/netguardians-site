package com.logicielpourtous.site.features.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.user.Appartenance;
import com.logicielpourtous.site.features.user.User;

/**
 * Selecteur de societe a la connexion web : une personne qui appartient a plusieurs societes doit en
 * choisir une avant d'acceder a quoi que ce soit ; le choix remplace l'authentification de la session.
 * Comptes construits en memoire (seuls les tenants et une fiche client sont enregistres).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ChoixSocieteControllerTest {

    private static final long ID_ALPHA = 9_100_001L;
    private static final long ID_BETA = 9_100_002L;

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private CustomerRepository customerRepository;

    private Tenant alpha;
    private Tenant beta;
    private Long ficheId;

    @BeforeEach
    void preparer() {
        String suffixe = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        alpha = tenant("Societe Alpha " + suffixe, "test_choix_alpha_" + suffixe);
        beta = tenant("Societe Beta " + suffixe, "test_choix_beta_" + suffixe);
        Customer fiche = new Customer();
        fiche.setCompanyName("Fiche Dupont " + suffixe);
        ficheId = customerRepository.save(fiche).getId();
    }

    // ── En attente de choix ─────────────────────────────────────────────────

    @Test
    void enAttenteDeChoix_toutesLesPagesRedirigentVersLeSelecteur() throws Exception {
        CustomUserDetails enAttente = multiSocietes(null);
        for (String page : new String[] {"/intra", "/", "/espace", "/mon-compte"}) {
            mockMvc.perform(get(page).with(user(enAttente)))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/choisir-societe"));
        }
    }

    @Test
    void enAttenteDeChoix_lesApisLeWebSocketEtLesEcrituresSontRefusees() throws Exception {
        // Ruling : hors navigation HTML (API web, poignee de main WebSocket, POST), refus 403 et non redirection.
        CustomUserDetails enAttente = multiSocietes(null);
        mockMvc.perform(get("/api/v1/shop/produits").with(user(enAttente)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/ws/info").with(user(enAttente)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/ws").with(user(enAttente)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/intra/settings/site").with(user(enAttente)).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/intra").with(user(enAttente)).header("Accept", "application/json"))
                .andExpect(status().isForbidden());
    }

    @Test
    void enAttenteDeChoix_leSelecteurListeSesSocietes() throws Exception {
        String html = mockMvc.perform(get("/choisir-societe").with(user(multiSocietes(null))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains(alpha.getName(), beta.getName());
        assertThat(html).contains("Fiche Dupont");
        assertThat(html).contains("name=\"_csrf\"");
        assertThat(html).contains("value=\"" + ID_ALPHA + "\"", "value=\"" + ID_BETA + "\"");
    }

    @Test
    void enAttenteDeChoix_laDeconnexionResteAccessible() throws Exception {
        mockMvc.perform(post("/logout").with(user(multiSocietes(null))).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout"));
    }

    // ── Choix ───────────────────────────────────────────────────────────────

    @Test
    void choisir_unePourLaPersonne_activeLaSocieteEtMetAJourLeTenantDeSession() throws Exception {
        MvcResult r = mockMvc.perform(post("/choisir-societe").with(user(multiSocietes(null))).with(csrf())
                        .param("appartenanceId", String.valueOf(ID_ALPHA)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) r.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(session.getAttribute("CURRENT_TENANT_ID")).isEqualTo(alpha.getSchemaName());
        CustomUserDetails actif = principalDeSession(session);
        assertThat(actif.selectionRequise()).isFalse();
        assertThat(actif.appartenanceActiveId()).isEqualTo(ID_ALPHA);
        assertThat(autorites(session)).contains("ROLE_ADMIN").doesNotContain("ROLE_SELECTION_SOCIETE");

        // La page suivante, avec la seule session (sans user()), n'est plus redirigee vers le selecteur.
        int statut = mockMvc.perform(get("/").session(session)).andReturn().getResponse().getStatus();
        assertThat(statut).isEqualTo(200);

        // Autre societe : pas d'autorite ADMIN.
        MvcResult r2 = mockMvc.perform(post("/choisir-societe").with(user(multiSocietes(null))).with(csrf())
                        .param("appartenanceId", String.valueOf(ID_BETA)))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession session2 = (MockHttpSession) r2.getRequest().getSession(false);
        assertThat(session2.getAttribute("CURRENT_TENANT_ID")).isEqualTo(beta.getSchemaName());
        assertThat(autorites(session2)).contains("ROLE_CLIENT").doesNotContain("ROLE_ADMIN");
    }

    @Test
    void choisir_uneAppartenanceDUnAutre_estRefuseeEtNeChangeRien() throws Exception {
        MvcResult r = mockMvc.perform(post("/choisir-societe").with(user(multiSocietes(null))).with(csrf())
                        .param("appartenanceId", "424242"))
                .andExpect(status().isForbidden())
                .andReturn();
        MockHttpSession session = (MockHttpSession) r.getRequest().getSession(false);
        if (session != null) {
            assertThat(session.getAttribute("CURRENT_TENANT_ID")).isNull();
            Object ctx = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            if (ctx != null) {
                assertThat(((CustomUserDetails) ((SecurityContext) ctx).getAuthentication().getPrincipal())
                        .selectionRequise()).isTrue();
            }
        }
    }

    @Test
    void choisir_sansJeton_estRefuse() throws Exception {
        mockMvc.perform(post("/choisir-societe").with(user(multiSocietes(null)))
                        .param("appartenanceId", String.valueOf(ID_ALPHA)))
                .andExpect(status().isForbidden());
    }

    @Test
    void choisir_sansEtreConnecte_renvoieALaConnexion() throws Exception {
        mockMvc.perform(get("/choisir-societe"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    // ── Une seule societe ───────────────────────────────────────────────────

    @Test
    void unCompteAvecUneSeuleSociete_neVoitJamaisLeSelecteur() throws Exception {
        CustomUserDetails seul = new CustomUserDetails(personne(alpha, Set.of(new Role("CLIENT", "Client", "CLIENT"))));
        int statut = mockMvc.perform(get("/").with(user(seul))).andReturn().getResponse().getStatus();
        assertThat(statut).isEqualTo(200);
        // Ruling : le selecteur lui-meme renvoie a l'accueil, et un choix est refuse.
        mockMvc.perform(get("/choisir-societe").with(user(seul)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
        mockMvc.perform(post("/choisir-societe").with(user(seul)).with(csrf()).param("appartenanceId", "1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void lienChangerDeSociete_n_apparaitQueSiPlusieursSocietes() throws Exception {
        String avec = mockMvc.perform(get("/").with(user(multiSocietes(ID_ALPHA))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(avec).contains("href=\"/choisir-societe\"");

        CustomUserDetails seul = new CustomUserDetails(personne(alpha, Set.of(new Role("CLIENT", "Client", "CLIENT"))));
        String sans = mockMvc.perform(get("/").with(user(seul)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(sans).doesNotContain("href=\"/choisir-societe\"");
    }

    // ── Outils ──────────────────────────────────────────────────────────────

    private Tenant tenant(String nom, String schema) {
        Tenant t = new Tenant();
        t.setName(nom);
        t.setSchemaName(schema);
        return tenantRepository.save(t);
    }

    private User personne(Tenant fiche, Set<Role> roles) {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", 8_800_001L);
        u.setEmail("choix-" + UUID.randomUUID() + "@exemple.fr");
        u.setPassword("x");
        u.setFullName("Personne Multi");
        u.setActive(true);
        u.setTenant(fiche);
        u.setRoles(roles);
        return u;
    }

    /** Personne membre d'Alpha (ADMIN, sans fiche) et de Beta (CLIENT, fiche Dupont). */
    private CustomUserDetails multiSocietes(Long active) {
        User u = personne(alpha, Set.of(new Role("ADMIN", "Admin", "ESN")));
        Appartenance a = appartenance(ID_ALPHA, alpha, null, new Role("ADMIN", "Admin", "ESN"));
        Appartenance b = appartenance(ID_BETA, beta, ficheId, new Role("CLIENT", "Client", "CLIENT"));
        return new CustomUserDetails(u, List.of(a, b), active);
    }

    private static Appartenance appartenance(long id, Tenant t, Long customerId, Role role) {
        Appartenance a = new Appartenance();
        a.setId(id);
        a.setUserId(8_800_001L);
        a.setTenant(t);
        a.setCustomerId(customerId);
        a.setRoles(Set.of(role));
        return a;
    }

    private static CustomUserDetails principalDeSession(MockHttpSession session) {
        SecurityContext ctx = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(ctx).as("contexte de securite enregistre en session").isNotNull();
        return (CustomUserDetails) ctx.getAuthentication().getPrincipal();
    }

    private static Set<String> autorites(MockHttpSession session) {
        SecurityContext ctx = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        return ctx.getAuthentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }
}
