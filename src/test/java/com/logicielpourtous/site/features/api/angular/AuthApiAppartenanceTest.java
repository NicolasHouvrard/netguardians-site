package com.logicielpourtous.site.features.api.angular;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;
import com.logicielpourtous.site.config.JwtTokenProvider;
import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.auth.CustomUserDetailsService;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.role.RoleRepository;
import com.logicielpourtous.site.features.user.Appartenance;
import com.logicielpourtous.site.features.user.AppartenanceRepository;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;

/**
 * Societe active dans l'API (JWT) : une personne de plusieurs societes recoit a la connexion un jeton de
 * selection (sans societe, qui n'ouvre que {@code POST /api/auth/societe}), puis un jeton portant la societe
 * choisie ; chaque requete revalide l'appartenance en base.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthApiAppartenanceTest {

    private static final String MOT_DE_PASSE = "Secret-123!";

    /**
     * Secret de test d'au moins 32 octets : la valeur par defaut d'application.properties est trop courte pour
     * HMAC-SHA. Pose sur le bean (puis restaure) plutot que par une propriete de test : un contexte Spring de
     * plus ouvrirait un pool de connexions de plus et epuiserait les connexions Postgres de la suite complete.
     */
    private static final String SECRET_DE_TEST = "secret-de-test-appartenance-au-moins-32-octets";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private AppartenanceRepository appartenanceRepository;
    @Autowired private CustomUserDetailsService userDetailsService;

    @Autowired private AuthApiController authApiController;

    /**
     * Verification Google simulee, posee sur le controleur (puis restauree) plutot que par @MockitoBean, pour
     * la meme raison que le secret : pas de contexte Spring supplementaire.
     */
    private final GoogleAuthService googleAuthService = mock(GoogleAuthService.class);
    private Object secretOrigine;
    private Object googleOrigine;

    private Tenant alpha;
    private Tenant beta;
    private User personneMulti;
    private User personneSeule;
    private Long idAlpha;
    private Long idBeta;
    private Long idAutre;

    @BeforeEach
    void preparer() {
        secretOrigine = ReflectionTestUtils.getField(jwtTokenProvider, "secret");
        ReflectionTestUtils.setField(jwtTokenProvider, "secret", SECRET_DE_TEST);
        Object controleur = AopTestUtils.getUltimateTargetObject(authApiController);
        googleOrigine = ReflectionTestUtils.getField(controleur, "googleAuthService");
        ReflectionTestUtils.setField(controleur, "googleAuthService", googleAuthService);

        String suffixe = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        alpha = tenant("Societe Alpha " + suffixe, "test_jwt_alpha_" + suffixe);
        beta = tenant("Societe Beta " + suffixe, "test_jwt_beta_" + suffixe);
        Role admin = role("ADMIN", "ESN");
        Role client = role("CLIENT", "CLIENT");

        // Personne de deux societes : ADMIN dans Alpha, CLIENT dans Beta.
        personneMulti = personne("multi", alpha, admin);
        idAlpha = enregistrer(personneMulti, alpha, admin).getId();
        idBeta = enregistrer(personneMulti, beta, client).getId();

        // Personne d'une seule societe (Alpha, ADMIN) : comportement d'avant.
        personneSeule = personne("seule", alpha, admin);
        enregistrer(personneSeule, alpha, admin);

        // Une autre personne, de deux societes elle aussi : son appartenance a Beta n'est pas a personneMulti.
        User autre = personne("autre", alpha, admin);
        enregistrer(autre, alpha, admin);
        idAutre = enregistrer(autre, beta, admin).getId();
    }

    @AfterEach
    void restaurer() {
        ReflectionTestUtils.setField(jwtTokenProvider, "secret", secretOrigine);
        Object controleur = AopTestUtils.getUltimateTargetObject(authApiController);
        ReflectionTestUtils.setField(controleur, "googleAuthService", googleOrigine);
    }

    // ── Jeton ─────────────────────────────────────────────────────────────────

    @Test
    void jeton_conserveLaSocieteChoisie() {
        CustomUserDetails compte = (CustomUserDetails) userDetailsService.charger(personneMulti.getEmail(), idBeta);
        String jeton = jwtTokenProvider.generate(compte, idBeta);
        assertThat(jwtTokenProvider.extractAppartenanceId(jeton)).isEqualTo(idBeta);
        assertThat(jwtTokenProvider.extractUsername(jeton)).isEqualTo(personneMulti.getEmail());
        // Sans societe : pas de revendication.
        assertThat(jwtTokenProvider.extractAppartenanceId(jwtTokenProvider.generate(compte))).isNull();
    }

    // ── Une seule societe ─────────────────────────────────────────────────────

    @Test
    void unCompteAvecUneSeuleSociete_connexionInchangee() throws Exception {
        String reponse = connexion(personneSeule)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectionRequise").doesNotExist())
                .andExpect(jsonPath("$.societes").doesNotExist())
                .andExpect(jsonPath("$.tenantId").value(alpha.getId()))
                .andExpect(jsonPath("$.roles[?(@ == 'ROLE_ADMIN')]").exists())
                .andReturn().getResponse().getContentAsString();
        String jeton = JsonPath.read(reponse, "$.token");
        assertThat(jwtTokenProvider.extractAppartenanceId(jeton)).isNull();
        mockMvc.perform(get("/api/intra/admin/roles").header("Authorization", "Bearer " + jeton))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + jeton))
                .andExpect(status().isOk());
    }

    // ── Plusieurs societes : connexion ────────────────────────────────────────

    @Test
    void plusieursSocietes_laConnexionDemandeLeChoix_etLeJetonSansSocieteNOuvreRien() throws Exception {
        String reponse = connexion(personneMulti)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectionRequise").value(true))
                .andExpect(jsonPath("$.societes.length()").value(2))
                .andExpect(jsonPath("$.societes[0].id").value(idAlpha))
                .andExpect(jsonPath("$.societes[0].libelle").value(alpha.getName()))
                .andExpect(jsonPath("$.societes[1].id").value(idBeta))
                .andExpect(jsonPath("$.societes[1].libelle").value(beta.getName()))
                .andExpect(jsonPath("$.roles").doesNotExist())
                .andExpect(jsonPath("$.tenantId").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String jeton = JsonPath.read(reponse, "$.token");
        assertThat(jwtTokenProvider.extractAppartenanceId(jeton)).isNull();

        // Appel protege par ROLE_ADMIN : 403.
        mockMvc.perform(get("/api/intra/admin/roles").header("Authorization", "Bearer " + jeton))
                .andExpect(status().isForbidden());
        // Rien d'autre n'est ouvert : ni /me, ni l'espace, ni l'API mobile.
        refuse(get("/api/auth/me"), jeton);
        refuse(get("/api/intra/admin/stats"), jeton);
        refuse(get("/api/espace/factures"), jeton);
        refuse(get("/api/v1/mobile/interventions"), jeton);
    }

    @Test
    void plusieursSocietes_unJetonCompletSansSociete_nOuvreRien_niLeChoix() throws Exception {
        // Jeton "complet" sans revendication (emis avant que la personne ait une 2e societe, ou forge) :
        // la personne est en attente de choix, aucune chaine ne l'authentifie.
        String jeton = jwtTokenProvider.generate(userDetailsService.charger(personneMulti.getEmail(), null));
        refuse(get("/api/auth/me"), jeton);
        refuse(get("/api/intra/admin/roles"), jeton);
        refuse(get("/api/v1/mobile/interventions"), jeton);
        refuse(post("/api/auth/societe").contentType(MediaType.APPLICATION_JSON)
                .content("{\"appartenanceId\":" + idAlpha + "}"), jeton);
    }

    @Test
    void jetonDeSelection_nOuvreRienAutreQueLeChoix_memeSiLaPersonneNAPlusQuUneSociete() throws Exception {
        String jeton = jetonDeSelection();
        appartenanceRepository.deleteById(idBeta);
        appartenanceRepository.flush();
        // La personne n'a plus qu'une societe : le jeton de selection ne devient pas un jeton complet.
        refuse(get("/api/auth/me"), jeton);
        refuse(get("/api/intra/admin/roles"), jeton);
        refuse(get("/api/v1/mobile/interventions"), jeton);
    }

    @Test
    void connexionGoogle_plusieursSocietes_demandeLeChoix() throws Exception {
        when(googleAuthService.verify(anyString()))
                .thenReturn(new GoogleAuthService.GoogleUserInfo("sub-1", personneMulti.getEmail(), "Personne Multi"));
        String reponse = mockMvc.perform(post("/api/auth/google").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credential\":\"x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectionRequise").value(true))
                .andExpect(jsonPath("$.societes.length()").value(2))
                .andExpect(jsonPath("$.roles").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String jeton = JsonPath.read(reponse, "$.token");
        refuse(get("/api/auth/me"), jeton);
        // Le jeton permet le choix.
        mockMvc.perform(choix(idAlpha, jeton)).andExpect(status().isOk());
    }

    @Test
    void connexionMobile_plusieursSocietes_aucunJeton() throws Exception {
        String reponse = mockMvc.perform(post("/api/v1/mobile/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(identifiants(personneMulti)))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();
        assertThat(reponse).doesNotContain("token");
        // Une seule societe : inchange.
        mockMvc.perform(post("/api/v1/mobile/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(identifiants(personneSeule)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists());
    }

    // ── Plusieurs societes : choix ────────────────────────────────────────────

    @Test
    void choisirUneSociete_renvoieUnJetonQuiOuvreLesDroitsDeCetteSociete() throws Exception {
        String selection = jetonDeSelection();

        String reponseAlpha = mockMvc.perform(choix(idAlpha, selection))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(alpha.getId()))
                .andExpect(jsonPath("$.roles[?(@ == 'ROLE_ADMIN')]").exists())
                .andReturn().getResponse().getContentAsString();
        String jetonAlpha = JsonPath.read(reponseAlpha, "$.token");
        assertThat(jwtTokenProvider.extractAppartenanceId(jetonAlpha)).isEqualTo(idAlpha);
        mockMvc.perform(get("/api/intra/admin/roles").header("Authorization", "Bearer " + jetonAlpha))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + jetonAlpha))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(alpha.getId()));

        // Changement de societe avec le jeton complet : Beta (CLIENT), pas de droits ADMIN.
        String reponseBeta = mockMvc.perform(choix(idBeta, jetonAlpha))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String jetonBeta = JsonPath.read(reponseBeta, "$.token");
        assertThat(jwtTokenProvider.extractAppartenanceId(jetonBeta)).isEqualTo(idBeta);
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + jetonBeta))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(beta.getId()))
                .andExpect(jsonPath("$.roles[?(@ == 'ROLE_CLIENT')]").exists())
                .andExpect(jsonPath("$.roles[?(@ == 'ROLE_ADMIN')]").doesNotExist());
        mockMvc.perform(get("/api/intra/admin/roles").header("Authorization", "Bearer " + jetonBeta))
                .andExpect(status().isForbidden());
    }

    @Test
    void uneAppartenanceRetiree_sonJetonCesseDeFonctionnerAussitot() throws Exception {
        String jetonBeta = JsonPath.read(mockMvc.perform(choix(idBeta, jetonDeSelection()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.token");
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + jetonBeta))
                .andExpect(status().isOk());

        appartenanceRepository.deleteById(idBeta);
        appartenanceRepository.flush();
        // La personne n'a plus qu'Alpha : le jeton de Beta n'ouvre pas Alpha a sa place.
        refuse(get("/api/auth/me"), jetonBeta);
        refuse(get("/api/intra/admin/roles"), jetonBeta);
    }

    @Test
    void unCompteDesactive_sonJetonCesseDeFonctionner() throws Exception {
        String jetonAlpha = JsonPath.read(mockMvc.perform(choix(idAlpha, jetonDeSelection()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.token");
        personneMulti.setActive(false);
        userRepository.saveAndFlush(personneMulti);
        refuse(get("/api/auth/me"), jetonAlpha);
        refuse(get("/api/intra/admin/roles"), jetonAlpha);
    }

    @Test
    void choisirLaSocieteDUnAutre_est403() throws Exception {
        String selection = jetonDeSelection();
        mockMvc.perform(choix(idAutre, selection)).andExpect(status().isForbidden());
        mockMvc.perform(choix(987_654_321L, selection)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/auth/societe").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("Authorization", "Bearer " + selection))
                .andExpect(status().isForbidden());
        // Sans jeton : refuse.
        int sansJeton = mockMvc.perform(post("/api/auth/societe").contentType(MediaType.APPLICATION_JSON)
                .content("{\"appartenanceId\":" + idAlpha + "}")).andReturn().getResponse().getStatus();
        assertThat(sansJeton).isIn(401, 403);
        // Une seule societe : rien a choisir.
        String jetonSeule = JsonPath.read(connexion(personneSeule).andReturn().getResponse().getContentAsString(), "$.token");
        Long idSeule = appartenanceRepository.findByUserIdOrderById(personneSeule.getId()).get(0).getId();
        mockMvc.perform(choix(idSeule, jetonSeule)).andExpect(status().isForbidden());
    }

    @Test
    void uneRevendicationVersLAppartenanceDUnAutre_laisseLaPersonneEnAttente() throws Exception {
        CustomUserDetails compte = (CustomUserDetails) userDetailsService.charger(personneMulti.getEmail(), null);
        String falsifie = jwtTokenProvider.generate(compte, idAutre);
        refuse(get("/api/auth/me"), falsifie);
        refuse(get("/api/intra/admin/roles"), falsifie);
        refuse(get("/api/v1/mobile/interventions"), falsifie);
        // Revendication vers une appartenance de l'autre personne, pour un compte d'une seule societe : refus aussi.
        String falsifieSeule = jwtTokenProvider.generate(
                userDetailsService.charger(personneSeule.getEmail(), null), idAutre);
        refuse(get("/api/auth/me"), falsifieSeule);
    }

    // ── Pont de session web -> jeton ──────────────────────────────────────────

    @Test
    void pontDeSession_unCompteAvecSocieteActive_recoitUnJetonPortantCetteSociete() throws Exception {
        CustomUserDetails actif = (CustomUserDetails) userDetailsService.charger(personneMulti.getEmail(), idAlpha);
        String reponse = mockMvc.perform(get("/auth/session-token").with(user(actif))
                        .header("Accept", "application/json"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String jeton = JsonPath.read(reponse, "$.token");
        assertThat(jwtTokenProvider.extractAppartenanceId(jeton)).isEqualTo(idAlpha);
        mockMvc.perform(get("/api/intra/admin/roles").header("Authorization", "Bearer " + jeton))
                .andExpect(status().isOk());

        // En attente de choix : aucun jeton.
        CustomUserDetails enAttente = (CustomUserDetails) userDetailsService.charger(personneMulti.getEmail(), null);
        int statut = mockMvc.perform(get("/auth/session-token").with(user(enAttente))
                .header("Accept", "application/json")).andReturn().getResponse().getStatus();
        assertThat(statut).isIn(401, 403);
    }

    // ── Outils ────────────────────────────────────────────────────────────────

    private ResultActions connexion(User u) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(identifiants(u)));
    }

    private String identifiants(User u) {
        return "{\"email\":\"" + u.getEmail() + "\",\"password\":\"" + MOT_DE_PASSE + "\"}";
    }

    private String jetonDeSelection() throws Exception {
        return JsonPath.read(connexion(personneMulti).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.token");
    }

    private static org.springframework.test.web.servlet.RequestBuilder choix(Long appartenanceId, String jeton) {
        return post("/api/auth/societe").contentType(MediaType.APPLICATION_JSON)
                .content("{\"appartenanceId\":" + appartenanceId + "}")
                .header("Authorization", "Bearer " + jeton);
    }

    /** La requete, avec ce jeton, n'aboutit pas (aucune authentification ou droits insuffisants). */
    private void refuse(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder requete,
                        String jeton) throws Exception {
        int statut = mockMvc.perform(requete.header("Authorization", "Bearer " + jeton))
                .andReturn().getResponse().getStatus();
        assertThat(statut).as("statut de " + requete).isIn(401, 403);
    }

    private Tenant tenant(String nom, String schema) {
        Tenant t = new Tenant();
        t.setName(nom);
        t.setSchemaName(schema);
        return tenantRepository.save(t);
    }

    private User personne(String prefixe, Tenant fiche, Role role) {
        User u = new User();
        u.setEmail(prefixe + "-" + UUID.randomUUID() + "@exemple.fr");
        u.setPassword(passwordEncoder.encode(MOT_DE_PASSE));
        u.setFullName("Personne " + prefixe);
        u.setActive(true);
        u.setTenant(fiche);
        u.setRoles(new java.util.HashSet<>(Set.of(role)));
        return userRepository.saveAndFlush(u);
    }

    private Role role(String nom, String type) {
        return roleRepository.findByName(nom).orElseGet(() -> roleRepository.save(new Role(nom, nom, type)));
    }

    private Appartenance enregistrer(User u, Tenant t, Role role) {
        Appartenance a = new Appartenance();
        a.setUserId(u.getId());
        a.setTenant(t);
        a.setRoles(new java.util.HashSet<>(Set.of(role)));
        return appartenanceRepository.saveAndFlush(a);
    }
}
