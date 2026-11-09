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

import org.junit.jupiter.api.AfterEach;
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
import com.logicielpourtous.site.features.role.RoleRepository;
import com.logicielpourtous.site.features.user.AppartenanceRepository;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;

/**
 * Selecteur de societe a la connexion web : une personne qui appartient a plusieurs societes doit en
 * choisir une avant d'acceder a quoi que ce soit ; le choix remplace l'authentification de la session.
 * Comptes construits en memoire (seuls les tenants et une fiche client sont enregistres).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ChoixSocieteControllerTest {

    /**
     * Secret de test d'au moins 32 octets : la valeur par defaut d'application.properties est trop courte pour
     * HMAC-SHA. Pose sur le bean (puis restaure) plutot que par une propriete de test : un contexte Spring de
     * plus ouvrirait un pool de connexions de plus et epuiserait les connexions Postgres de la suite complete.
     */
    private static final String SECRET_DE_TEST = "secret-de-test-appartenance-au-moins-32-octets";
    private Object secretOrigine;

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private AppartenanceRepository appartenanceRepository;
    @Autowired private CustomUserDetailsService userDetailsService;
    @Autowired private com.logicielpourtous.site.config.JwtTokenProvider jwtTokenProvider;

    @org.springframework.beans.factory.annotation.Value("${app.angular.login-redirect:http://localhost:4200/login}")
    private String angularLoginRedirect;

    private Tenant alpha;
    private Tenant beta;
    private Long ficheId;
    private User personneMulti;
    private Long idAlpha;
    private Long idBeta;

    @BeforeEach
    void preparer() {
        secretOrigine = ReflectionTestUtils.getField(jwtTokenProvider, "secret");
        ReflectionTestUtils.setField(jwtTokenProvider, "secret", SECRET_DE_TEST);
        String suffixe = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        alpha = tenant("Societe Alpha " + suffixe, "test_choix_alpha_" + suffixe);
        beta = tenant("Societe Beta " + suffixe, "test_choix_beta_" + suffixe);
        Customer fiche = new Customer();
        fiche.setCompanyName("Fiche Dupont " + suffixe);
        ficheId = customerRepository.save(fiche).getId();

        // Personne enregistree : membre d'Alpha (ADMIN, sans fiche) et de Beta (CLIENT, fiche Dupont).
        Role admin = role("ADMIN", "ESN");
        Role client = role("CLIENT", "CLIENT");
        User u = new User();
        u.setEmail("choix-" + UUID.randomUUID() + "@exemple.fr");
        u.setPassword("x");
        u.setFullName("Personne Multi");
        u.setActive(true);
        u.setTenant(alpha);
        u.setRoles(new java.util.HashSet<>(Set.of(admin)));
        personneMulti = userRepository.save(u);
        idAlpha = enregistrer(personneMulti, alpha, null, admin).getId();
        idBeta = enregistrer(personneMulti, beta, ficheId, client).getId();
    }

    @AfterEach
    void restaurer() {
        ReflectionTestUtils.setField(jwtTokenProvider, "secret", secretOrigine);
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
        assertThat(html).contains("value=\"" + idAlpha + "\"", "value=\"" + idBeta + "\"");
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
                        .param("appartenanceId", String.valueOf(idAlpha)))
                .andExpect(status().is3xxRedirection())
                // Task 4 : meme destination que la connexion d'un compte d'une seule societe (ADMIN -> console).
                .andExpect(redirectedUrl("/admin/console"))
                .andReturn();
        // Jeton court pour l'auto-connexion Angular, portant la societe choisie.
        jakarta.servlet.http.Cookie cookie = r.getResponse().getCookie("_intra_jwt");
        assertThat(cookie).isNotNull();
        assertThat(jwtTokenProvider.extractAppartenanceId(cookie.getValue())).isEqualTo(idAlpha);
        MockHttpSession session = (MockHttpSession) r.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(session.getAttribute("CURRENT_TENANT_ID")).isEqualTo(alpha.getSchemaName());
        CustomUserDetails actif = principalDeSession(session);
        assertThat(actif.selectionRequise()).isFalse();
        assertThat(actif.appartenanceActiveId()).isEqualTo(idAlpha);
        assertThat(autorites(session)).contains("ROLE_ADMIN").doesNotContain("ROLE_SELECTION_SOCIETE");

        // La page suivante, avec la seule session (sans user()), n'est plus redirigee vers le selecteur.
        int statut = mockMvc.perform(get("/").session(session)).andReturn().getResponse().getStatus();
        assertThat(statut).isEqualTo(200);

        // Autre societe : pas d'autorite ADMIN.
        MvcResult r2 = mockMvc.perform(post("/choisir-societe").with(user(multiSocietes(null))).with(csrf())
                        .param("appartenanceId", String.valueOf(idBeta)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/espace"))
                .andReturn();
        assertThat(r2.getResponse().getCookie("_intra_jwt")).isNull();
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
                        .param("appartenanceId", String.valueOf(idAlpha)))
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
        String avec = mockMvc.perform(get("/").with(user(multiSocietes(idAlpha))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(avec).contains("href=\"/choisir-societe\"");

        CustomUserDetails seul = new CustomUserDetails(personne(alpha, Set.of(new Role("CLIENT", "Client", "CLIENT"))));
        String sans = mockMvc.perform(get("/").with(user(seul)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(sans).doesNotContain("href=\"/choisir-societe\"");
    }

    // ── Fix round 1 ─────────────────────────────────────────────────────────

    @Test
    void choisir_retireLesDonneesDeSessionDeLAncienneSociete() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("portalViewClientId", 77L);
        session.setAttribute("mindeeResult", "resultat");
        session.setAttribute("mindeeFilePath", "supplier-invoices/" + alpha.getSchemaName() + "/f.pdf");
        session.setAttribute("mindeeOriginalFilename", "f.pdf");
        session.setAttribute("lastIntraDashboardSlug", "tableau-alpha");
        session.setAttribute("chatConvId", 12L);
        session.setAttribute("attributInconnuDUnFuturEcran", "x");
        session.setAttribute("CURRENT_TENANT_ID", alpha.getSchemaName());
        session.setAttribute("MUST_CHANGE_PASSWORD", Boolean.TRUE);
        session.setAttribute("shopCart", "panier");

        MvcResult r = mockMvc.perform(post("/choisir-societe").session(session).with(user(multiSocietes(idAlpha)))
                        .with(csrf()).param("appartenanceId", String.valueOf(idBeta)))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession apres = (MockHttpSession) r.getRequest().getSession(false);
        List<String> noms = java.util.Collections.list(apres.getAttributeNames());
        assertThat(noms).doesNotContain("portalViewClientId", "mindeeResult", "mindeeFilePath",
                "mindeeOriginalFilename", "lastIntraDashboardSlug", "chatConvId", "attributInconnuDUnFuturEcran");
        assertThat(apres.getAttribute("CURRENT_TENANT_ID")).isEqualTo(beta.getSchemaName());
        assertThat(apres.getAttribute("MUST_CHANGE_PASSWORD")).isEqualTo(Boolean.TRUE);
        assertThat(apres.getAttribute("shopCart")).isEqualTo("panier");
        assertThat(principalDeSession(apres).appartenanceActiveId()).isEqualTo(idBeta);
    }

    @Test
    void choisir_uneAppartenanceSupprimeeDepuisLaConnexion_estRefusee_etLaSessionResteEnAttente() throws Exception {
        CustomUserDetails charge = multiSocietes(null); // charge a la connexion : Alpha et Beta
        appartenanceRepository.deleteById(idBeta);
        appartenanceRepository.flush();

        MvcResult r = mockMvc.perform(post("/choisir-societe").with(user(charge)).with(csrf())
                        .param("appartenanceId", String.valueOf(idBeta)))
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
    void choisir_compteDesactiveDepuisLaConnexion_estRefuse() throws Exception {
        CustomUserDetails charge = multiSocietes(null);
        personneMulti.setActive(false);
        userRepository.saveAndFlush(personneMulti);

        mockMvc.perform(post("/choisir-societe").with(user(charge)).with(csrf())
                        .param("appartenanceId", String.valueOf(idAlpha)))
                .andExpect(status().isForbidden());
    }

    // ── Task 4 : meme suite que la connexion ──────────────────────────────────

    @Test
    void choisir_apresUneConnexionDepuisAngular_renvoieAAngularAvecUnJetonDeLaSociete() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(CustomTenantSuccessHandler.ORIGINE_ANGULAR_EN_ATTENTE, Boolean.TRUE);
        MvcResult r = mockMvc.perform(post("/choisir-societe").session(session).with(user(multiSocietes(null)))
                        .with(csrf()).param("appartenanceId", String.valueOf(idBeta)))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String cible = r.getResponse().getRedirectedUrl();
        assertThat(cible).startsWith(angularLoginRedirect + "?token=");
        String jeton = cible.substring((angularLoginRedirect + "?token=").length());
        assertThat(jwtTokenProvider.extractAppartenanceId(jeton)).isEqualTo(idBeta);
        // Indicateur a usage unique.
        assertThat(r.getRequest().getSession(false).getAttribute(CustomTenantSuccessHandler.ORIGINE_ANGULAR_EN_ATTENTE))
                .isNull();
    }

    @Test
    void choisir_avecChangementDeMotDePasseEnAttente_renvoieAuChangementDeMotDePasse() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("MUST_CHANGE_PASSWORD", Boolean.TRUE);
        mockMvc.perform(post("/choisir-societe").session(session).with(user(multiSocietes(null)))
                        .with(csrf()).param("appartenanceId", String.valueOf(idAlpha)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/change-password"));
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

    /** Compte de la personne enregistree, tel que la connexion le charge (appartenance active eventuelle). */
    private CustomUserDetails multiSocietes(Long active) {
        return (CustomUserDetails) userDetailsService.charger(personneMulti.getEmail(), active);
    }

    private Role role(String nom, String type) {
        return roleRepository.findByName(nom).orElseGet(() -> roleRepository.save(new Role(nom, nom, type)));
    }

    private Appartenance enregistrer(User u, Tenant t, Long customerId, Role role) {
        Appartenance a = new Appartenance();
        a.setUserId(u.getId());
        a.setTenant(t);
        a.setCustomerId(customerId);
        a.setRoles(new java.util.HashSet<>(Set.of(role)));
        return appartenanceRepository.saveAndFlush(a);
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
