package com.logicielpourtous.site.features.licence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.user.User;

/**
 * Ecran admin {@code /intra/licence/comptes} : creation (hash du mot de passe via le
 * {@code PasswordEncoder} partage), liste, desactivation, et garde "disponible seulement
 * si {@code app.role=MASTER}" ({@link com.logicielpourtous.site.features.commun.ServerRoleProperties}).
 *
 * <p>Meme patron que {@code TicketRoutingRuleControllerTest} : le header Thymeleaf partage
 * ({@code templates/header/connected.html}) appelle {@code principal.hasEsnRole()} via
 * {@code sec:authorize}, une methode definie sur {@code CustomUserDetails} — pas sur le
 * principal generique que produit {@code @WithMockUser}. On construit donc un vrai
 * {@code CustomUserDetails} (adosse a une entite {@code User} minimale avec un role ADMIN
 * de type "ESN") et on l'injecte via {@code SecurityMockMvcRequestPostProcessors.user(...)}.
 * {@code ChatIntraAdvice}/{@code IntraNavAdvice} (@ControllerAdvice globaux actifs sur toute
 * requete {@code /intra/**}) exigent un {@code Tenant} reellement persiste (sinon
 * {@code TransientObjectException}) ; on le persiste donc avant de construire le principal.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CompteLicenceAdminControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private CompteLicenceRepository compteLicenceRepository;
    @Autowired private LicenceServeurRepository licenceServeurRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void createCompte_hashesPasswordAndPersists() throws Exception {
        mockMvc.perform(post("/intra/licence/comptes")
                        .with(user(adminPrincipal(tenantRepository))).with(csrf())
                        .param("login", "client-alpha-" + UUID.randomUUID())
                        .param("motDePasse", "secret-en-clair")
                        .param("typeAbonnement", "ILLIMITE")
                        .param("frequence", "MENSUEL")
                        .param("nombreServeursAutorises", "3"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/intra/licence/comptes").with(user(adminPrincipal(tenantRepository))).with(csrf()))
                .andExpect(status().isOk());

        CompteLicence compte = compteLicenceRepository.findAll().stream()
                .filter(c -> c.getLogin().startsWith("client-alpha-"))
                .findFirst().orElseThrow();
        assertThat(compte.getMotDePasseHash()).isNotEqualTo("secret-en-clair");
        assertThat(passwordEncoder.matches("secret-en-clair", compte.getMotDePasseHash())).isTrue();
        assertThat(compte.isActif()).isTrue();
    }

    @Test
    void listComptes_showsActifAndServeurCount() throws Exception {
        CompteLicence compte = new CompteLicence();
        compte.setLogin("client-beta-" + UUID.randomUUID());
        compte.setMotDePasseHash(passwordEncoder.encode("x"));
        compte.setTypeAbonnement(CompteLicence.TypeAbonnement.ILLIMITE);
        compte.setFrequence(CompteLicence.Frequence.ANNUEL);
        compte.setNombreServeursAutorises(7);
        compte = compteLicenceRepository.save(compte);

        mockMvc.perform(get("/intra/licence/comptes").with(user(adminPrincipal(tenantRepository))).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(compte.getLogin())))
                .andExpect(content().string(containsString("7")));
    }

    @Test
    void deactivateCompte_setsActifFalse() throws Exception {
        CompteLicence compte = new CompteLicence();
        compte.setLogin("client-gamma-" + UUID.randomUUID());
        compte.setMotDePasseHash(passwordEncoder.encode("x"));
        compte.setTypeAbonnement(CompteLicence.TypeAbonnement.ILLIMITE);
        compte.setFrequence(CompteLicence.Frequence.MENSUEL);
        compte.setNombreServeursAutorises(1);
        compte = compteLicenceRepository.save(compte);
        assertThat(compte.isActif()).isTrue();

        mockMvc.perform(post("/intra/licence/comptes/{id}/toggle-actif", compte.getId())
                        .with(user(adminPrincipal(tenantRepository))).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(compteLicenceRepository.findById(compte.getId()).orElseThrow().isActif()).isFalse();
    }

    // Revue finale, I2 : desactiver un compte doit COUPER l'acces actif de tous ses serveurs et le
    // propager au partenaire — meme mecanisme que revokeLicenceServeur (REVOQUE + notifiePartenaire=false).
    @Test
    void deactivateCompte_revokesAllItsActiveLicenceServeurs() throws Exception {
        CompteLicence compte = compteAvecQuota(5);
        LicenceServeur partner = licenceServeurDuCompte(compte, "10.0.0.5");
        partner.setNotifiePartenaire(true);
        licenceServeurRepository.save(partner);
        LicenceServeur client = new LicenceServeur();
        client.setCompteId(compte.getId());
        client.setRole(com.logicielpourtous.site.features.commun.ServerRole.CLIENT);
        client.setCustomerId(42L);
        client.setIdentifiantInstallationStable(UUID.randomUUID().toString());
        client.setStatut(LicenceServeur.LicenceServeurStatut.ACTIF);
        client.setNotifiePartenaire(true); // deja decouvert : la revocation doit le faire re-sonder
        client = licenceServeurRepository.save(client);

        mockMvc.perform(post("/intra/licence/comptes/{id}/toggle-actif", compte.getId())
                        .with(user(adminPrincipal(tenantRepository))).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(compteLicenceRepository.findById(compte.getId()).orElseThrow().isActif()).isFalse();
        for (Long id : java.util.List.of(partner.getId(), client.getId())) {
            LicenceServeur apres = licenceServeurRepository.findById(id).orElseThrow();
            assertThat(apres.getStatut()).isEqualTo(LicenceServeur.LicenceServeurStatut.REVOQUE);
            assertThat(apres.isNotifiePartenaire()).isFalse();
        }
    }

    // Revue finale, I2 : reactiver le compte ne doit JAMAIS re-activer automatiquement un serveur
    // revoque par la desactivation (aucun "un-revoke" n'existe, meme regle que la revocation unitaire).
    @Test
    void reactivateCompte_doesNotRestoreServeursRevokedByDeactivation() throws Exception {
        CompteLicence compte = compteAvecQuota(5);
        LicenceServeur serveur = licenceServeurDuCompte(compte, "10.0.0.5");

        mockMvc.perform(post("/intra/licence/comptes/{id}/toggle-actif", compte.getId())
                        .with(user(adminPrincipal(tenantRepository))).with(csrf()))
                .andExpect(status().is3xxRedirection()); // desactivation
        mockMvc.perform(post("/intra/licence/comptes/{id}/toggle-actif", compte.getId())
                        .with(user(adminPrincipal(tenantRepository))).with(csrf()))
                .andExpect(status().is3xxRedirection()); // reactivation

        assertThat(compteLicenceRepository.findById(compte.getId()).orElseThrow().isActif()).isTrue();
        assertThat(licenceServeurRepository.findById(serveur.getId()).orElseThrow().getStatut())
                .isEqualTo(LicenceServeur.LicenceServeurStatut.REVOQUE);
    }

    // ── Task 2 : detail d'un compte et ses LicenceServeur (Step 9) ────────────

    @Test
    void compteDetail_listsItsLicenceServeurs() throws Exception {
        CompteLicence compte = compteAvecQuota(5);
        LicenceServeur serveur = licenceServeurDuCompte(compte, "10.0.0.5");

        mockMvc.perform(get("/intra/licence/comptes/{id}", compte.getId()).with(user(adminPrincipal(tenantRepository))).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(serveur.getIdentifiantInstallationStable())))
                .andExpect(content().string(containsString("10.0.0.5")));
    }

    @Test
    void updateAdresseDeclaree_changesTheStoredValue() throws Exception {
        CompteLicence compte = compteAvecQuota(5);
        LicenceServeur serveur = licenceServeurDuCompte(compte, "10.0.0.5");

        mockMvc.perform(post("/intra/licence/comptes/{compteId}/serveurs/{serveurId}/adresse",
                        compte.getId(), serveur.getId())
                        .with(user(adminPrincipal(tenantRepository))).with(csrf())
                        .param("adresseDeclaree", "192.168.99.99"))
                .andExpect(status().is3xxRedirection());

        assertThat(licenceServeurRepository.findById(serveur.getId()).orElseThrow().getAdresseDeclaree())
                .isEqualTo("192.168.99.99");
    }

    @Test
    void revokeOneLicenceServeur_setsStatutRevoque_accountKeepsOthersActive() throws Exception {
        CompteLicence compte = compteAvecQuota(5);
        LicenceServeur aRevoquer = licenceServeurDuCompte(compte, "10.0.0.5");
        LicenceServeur autreServeur = licenceServeurDuCompte(compte, "10.0.0.6");

        mockMvc.perform(post("/intra/licence/comptes/{compteId}/serveurs/{serveurId}/revoquer",
                        compte.getId(), aRevoquer.getId())
                        .with(user(adminPrincipal(tenantRepository))).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(licenceServeurRepository.findById(aRevoquer.getId()).orElseThrow().getStatut())
                .isEqualTo(LicenceServeur.LicenceServeurStatut.REVOQUE);
        assertThat(licenceServeurRepository.findById(autreServeur.getId()).orElseThrow().getStatut())
                .isEqualTo(LicenceServeur.LicenceServeurStatut.ACTIF);
    }

    // Task 6 : une revocation doit etre RE-SONDEE par le PARTNER (GET /api/licence/nouveaux-serveurs)
    // meme si ce serveur avait deja ete notifie a sa creation — sinon la revocation ne serait jamais
    // propagee localement (voir LicenceServeur#isNotifiePartenaire).
    @Test
    void revokeLicenceServeur_alreadyNotifiedPartner_resetsNotifiePartenaireForReDiscovery() throws Exception {
        CompteLicence compte = compteAvecQuota(5);
        LicenceServeur serveur = licenceServeurDuCompte(compte, "10.0.0.5");
        serveur.setNotifiePartenaire(true);
        licenceServeurRepository.save(serveur);

        mockMvc.perform(post("/intra/licence/comptes/{compteId}/serveurs/{serveurId}/revoquer",
                        compte.getId(), serveur.getId())
                        .with(user(adminPrincipal(tenantRepository))).with(csrf()))
                .andExpect(status().is3xxRedirection());

        LicenceServeur apres = licenceServeurRepository.findById(serveur.getId()).orElseThrow();
        assertThat(apres.getStatut()).isEqualTo(LicenceServeur.LicenceServeurStatut.REVOQUE);
        assertThat(apres.isNotifiePartenaire()).isFalse();
    }

    // Fix round 1, finding 4 : serveurId seul ne suffit pas, il doit appartenir au compteId du chemin.
    @Test
    void updateAdresseDeclaree_crossCompteMismatch_isRejected() throws Exception {
        CompteLicence compteA = compteAvecQuota(5);
        CompteLicence compteB = compteAvecQuota(5);
        LicenceServeur serveurDeB = licenceServeurDuCompte(compteB, "10.0.0.5");

        mockMvc.perform(post("/intra/licence/comptes/{compteId}/serveurs/{serveurId}/adresse",
                        compteA.getId(), serveurDeB.getId())
                        .with(user(adminPrincipal(tenantRepository))).with(csrf())
                        .param("adresseDeclaree", "192.168.99.99"))
                .andExpect(status().isNotFound());

        assertThat(licenceServeurRepository.findById(serveurDeB.getId()).orElseThrow().getAdresseDeclaree())
                .isEqualTo("10.0.0.5");
    }

    @Test
    void revokeLicenceServeur_crossCompteMismatch_isRejected() throws Exception {
        CompteLicence compteA = compteAvecQuota(5);
        CompteLicence compteB = compteAvecQuota(5);
        LicenceServeur serveurDeB = licenceServeurDuCompte(compteB, "10.0.0.5");

        mockMvc.perform(post("/intra/licence/comptes/{compteId}/serveurs/{serveurId}/revoquer",
                        compteA.getId(), serveurDeB.getId())
                        .with(user(adminPrincipal(tenantRepository))).with(csrf()))
                .andExpect(status().isNotFound());

        assertThat(licenceServeurRepository.findById(serveurDeB.getId()).orElseThrow().getStatut())
                .isEqualTo(LicenceServeur.LicenceServeurStatut.ACTIF);
    }

    private CompteLicence compteAvecQuota(int quota) {
        CompteLicence compte = new CompteLicence();
        compte.setLogin("compte-detail-" + UUID.randomUUID());
        compte.setMotDePasseHash(passwordEncoder.encode("x"));
        compte.setTypeAbonnement(CompteLicence.TypeAbonnement.ILLIMITE);
        compte.setFrequence(CompteLicence.Frequence.MENSUEL);
        compte.setNombreServeursAutorises(quota);
        return compteLicenceRepository.save(compte);
    }

    private LicenceServeur licenceServeurDuCompte(CompteLicence compte, String adresse) {
        LicenceServeur serveur = new LicenceServeur();
        serveur.setCompteId(compte.getId());
        serveur.setRole(com.logicielpourtous.site.features.commun.ServerRole.PARTNER);
        serveur.setIdentifiantInstallationStable(UUID.randomUUID().toString());
        serveur.setAdresseDeclaree(adresse);
        serveur.setStatut(LicenceServeur.LicenceServeurStatut.ACTIF);
        return licenceServeurRepository.save(serveur);
    }

    @Nested
    @TestPropertySource(properties = "app.role=partner")
    @Transactional
    class NotMasterTest {

        @Autowired private MockMvc mockMvc;
        @Autowired private TenantRepository tenantRepository;

        @Test
        void adminScreen_notAvailableWhenRoleIsNotMaster() throws Exception {
            mockMvc.perform(get("/intra/licence/comptes").with(user(adminPrincipal(tenantRepository))).with(csrf()))
                    .andExpect(status().isNotFound());
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
}
