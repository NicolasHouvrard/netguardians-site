package com.logicielpourtous.site.features.licence;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.logicielpourtous.site.features.commun.ServerRole;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.user.Appartenance;
import com.logicielpourtous.site.features.user.AppartenanceRepository;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;

/** {@code POST /api/licence/aiguillage} de bout en bout (securite, base reelle) ; le validateur de jeton est simule. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AiguillageControllerTest {

    private static final String CORPS = "{\"idToken\":\"jeton-bidon\"}";

    @Autowired private MockMvc mockMvc;
    @Autowired private LicenceServeurRepository licenceServeurRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AppartenanceRepository appartenanceRepository;
    @Autowired private com.logicielpourtous.site.features.identite.SocieteIdentiteRepository societeRepository;
    @MockitoBean private ValidateurJetonAuthelia validateur;

    private LicenceServeur serveur(String cle, LicenceServeur.LicenceServeurStatut statut, String clientId, String url) {
        LicenceServeur s = new LicenceServeur();
        s.setCompteId(1L);
        s.setRole(ServerRole.PARTNER);
        s.setIdentifiantInstallationStable(UUID.randomUUID().toString());
        s.setCleApiHash(LicenceCles.hash(cle));
        s.setStatut(statut);
        s.setOidcClientId(clientId);
        s.setUrlPublique(url);
        return licenceServeurRepository.save(s);
    }

    private Long societe() {
        var si = new com.logicielpourtous.site.features.identite.SocieteIdentite();
        si.setParticulier(true);
        return societeRepository.save(si).getId();
    }

    private void compteAvecSociete(String email, Tenant tenant, Long societeId) {
        User u = new User();
        u.setEmail(email);
        u.setPassword("x");
        u.setFullName("Alice");
        u.setActive(true);
        u.setTenant(tenant);
        u.setRoles(new java.util.HashSet<>(Set.of()));
        u = userRepository.save(u);
        Appartenance a = new Appartenance();
        a.setUserId(u.getId());
        a.setTenant(tenant);
        a.setSocieteId(societeId);
        appartenanceRepository.save(a);
    }

    private Tenant tenant(String nom, Long serveurLicenceId) {
        Tenant t = new Tenant();
        t.setName(nom);
        t.setSchemaName("aig_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        t.setServeurLicenceId(serveurLicenceId);
        return tenantRepository.save(t);
    }

    @Test
    void cle_licence_absente_ou_revoquee_donne_401() throws Exception {
        String cle = LicenceCles.generate();
        serveur(cle, LicenceServeur.LicenceServeurStatut.REVOQUE, "c1", "https://a.exemple.test");

        mockMvc.perform(post("/api/licence/aiguillage").contentType(MediaType.APPLICATION_JSON).content(CORPS))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/licence/aiguillage").contentType(MediaType.APPLICATION_JSON).content(CORPS)
                        .header(LicenceApiKeyAuthFilter.HEADER, cle))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/licence/aiguillage").contentType(MediaType.APPLICATION_JSON).content(CORPS)
                        .header(LicenceApiKeyAuthFilter.HEADER, LicenceCles.generate()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void jeton_invalide_donne_200_et_liste_vide() throws Exception {
        String cle = LicenceCles.generate();
        serveur(cle, LicenceServeur.LicenceServeurStatut.ACTIF, "c1", "https://a.exemple.test");
        when(validateur.valider(any(), any())).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/licence/aiguillage").contentType(MediaType.APPLICATION_JSON).content(CORPS)
                        .header(LicenceApiKeyAuthFilter.HEADER, cle))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serveurs").isArray())
                .andExpect(jsonPath("$.serveurs.length()").value(0));
        // corps sans jeton : meme reponse
        mockMvc.perform(post("/api/licence/aiguillage").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header(LicenceApiKeyAuthFilter.HEADER, cle))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serveurs.length()").value(0));
    }

    @Test
    void aucune_donnee_dans_la_reponse_hors_societe_nom_et_adresse() throws Exception {
        String cle = LicenceCles.generate();
        serveur(cle, LicenceServeur.LicenceServeurStatut.ACTIF, "c-appelant", "https://appelant.exemple.test");
        LicenceServeur vps = serveur(LicenceCles.generate(), LicenceServeur.LicenceServeurStatut.ACTIF, "c-vps",
                "https://vps.exemple.test");
        String email = "alice-" + UUID.randomUUID() + "@exemple.fr";
        Long societeId = societe();
        compteAvecSociete(email, tenant("Societe Alice", vps.getId()), societeId);
        when(validateur.valider(any(), eq("c-appelant"))).thenReturn(Optional.of(email));

        mockMvc.perform(post("/api/licence/aiguillage").contentType(MediaType.APPLICATION_JSON).content(CORPS)
                        .header(LicenceApiKeyAuthFilter.HEADER, cle))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$.serveurs.length()").value(1))
                .andExpect(jsonPath("$.serveurs[0].length()").value(3))
                .andExpect(jsonPath("$.serveurs[0].societeId").value(societeId.intValue()))
                .andExpect(jsonPath("$.serveurs[0].nomSociete").value("Societe Alice"))
                .andExpect(jsonPath("$.serveurs[0].urlPublique").value("https://vps.exemple.test"));
    }

    @Test
    void un_serveur_sans_oidc_client_id_est_refuse_comme_jeton_invalide() throws Exception {
        String cle = LicenceCles.generate();
        serveur(cle, LicenceServeur.LicenceServeurStatut.ACTIF, null, "https://appelant.exemple.test");
        // meme si le validateur accepterait tout, un serveur sans identifiant de client n'obtient rien
        when(validateur.valider(any(), any())).thenReturn(Optional.of("alice@exemple.fr"));

        mockMvc.perform(post("/api/licence/aiguillage").contentType(MediaType.APPLICATION_JSON).content(CORPS)
                        .header(LicenceApiKeyAuthFilter.HEADER, cle))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serveurs.length()").value(0));
    }
}
