package com.logicielpourtous.site.features.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.logicielpourtous.site.config.ModuleSecurityInterceptor;
import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.identite.NumerosSociete;
import com.logicielpourtous.site.features.registration.SubscriptionRequest.SubscriptionRequestStatus;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.user.User;

/**
 * Approbation / refus des demandes d'inscription ESN : l'approbation ecrit au registre une ligne PARTENAIRE
 * definitive (jamais liberee) ; elle est donc reservee aux administrateurs et protegee par CSRF.
 * {@code @Transactional} : tout est annule apres chaque test (l'approbation rejoint la transaction du test).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SubscriptionRequestSecuriteTest {

    private static final Pattern FORMULAIRE_POST = Pattern.compile("<form\\b[^>]*method=\"post\"[^>]*>",
            Pattern.CASE_INSENSITIVE);

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private SubscriptionRequestRepository requetes;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ModuleSecurityInterceptor modules;

    /**
     * Le filtrage par module ({@code admin_modules}, modifiable en base par un administrateur) peut deja
     * rediriger un non-admin ; il ne doit pas etre la seule protection. On le neutralise pour l'ecran des
     * inscriptions (suppression annulee avec la transaction du test) afin de verifier la regle du code.
     */
    @BeforeEach
    void sansFiltrageParModule() {
        jdbc.update("DELETE FROM dashboard_module_links WHERE module_id IN "
                + "(SELECT id FROM admin_modules WHERE url LIKE '/intra/registrations%')");
        jdbc.update("DELETE FROM intra_widgets WHERE module_id IN "
                + "(SELECT id FROM admin_modules WHERE url LIKE '/intra/registrations%')");
        jdbc.update("DELETE FROM admin_modules WHERE url LIKE '/intra/registrations%'");
        modules.clearCache();
    }

    @AfterEach
    void cacheDesModules() {
        modules.clearCache();
    }

    private static String nouveauSiren() {
        while (true) {
            String base = String.format("%08d", ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999));
            for (int cle = 0; cle <= 9; cle++) {
                String siren = base + cle;
                if (NumerosSociete.sirenValide(siren) && !siren.startsWith("356000000")) {
                    return siren;
                }
            }
        }
    }

    private SubscriptionRequest demandeEnAttente(String siren) {
        SubscriptionRequest req = new SubscriptionRequest();
        req.setCompanyName("ESN Securite Test");
        req.setContactName("Alice Martin");
        req.setEmail("esn-securite-" + UUID.randomUUID() + "@example.com");
        req.setSiren(siren);
        req.setVatNumber(NumerosSociete.tvaFrancaise(siren));
        return requetes.save(req);
    }

    private CustomUserDetails compte(String role) {
        Tenant tenant = new Tenant();
        tenant.setName("Tenant securite inscriptions");
        tenant.setSchemaName("test_securite_" + UUID.randomUUID());
        tenant = tenantRepository.save(tenant);
        User user = new User();
        user.setEmail("securite-" + UUID.randomUUID() + "@logicielpourtous.com");
        user.setPassword("test");
        user.setFullName("Test " + role);
        user.setActive(true);
        user.setTenant(tenant);
        user.setRoles(Set.of(new Role(role, role, "ESN")));
        return new CustomUserDetails(user);
    }

    private long lignesPartenaire(String siren) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM societes_identites WHERE siren = ? AND type = 'PARTENAIRE'",
                Long.class, siren);
    }

    private SubscriptionRequestStatus statut(Long id) {
        return requetes.findById(id).orElseThrow().getStatus();
    }

    // ── Roles ────────────────────────────────────────────────────────────

    @Test
    void roleIntraNonAdmin_approuverEtRefuser_interdits_rienEcrit() throws Exception {
        String siren = nouveauSiren();
        SubscriptionRequest req = demandeEnAttente(siren);
        CustomUserDetails technicien = compte("TECHNICIEN");

        mockMvc.perform(post("/intra/registrations/{id}/approve", req.getId()).with(user(technicien)).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/intra/registrations/{id}/reject", req.getId()).with(user(technicien)).with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(lignesPartenaire(siren)).isZero();
        assertThat(statut(req.getId())).isEqualTo(SubscriptionRequestStatus.PENDING);
    }

    @Test
    void roleIntraNonAdmin_listeInterdite() throws Exception {
        mockMvc.perform(get("/intra/registrations").with(user(compte("COMMERCIAL"))))
                .andExpect(status().isForbidden());
    }

    // ── CSRF ─────────────────────────────────────────────────────────────

    @Test
    void adminSansJetonCsrf_approuverEtRefuser_interdits_rienEcrit() throws Exception {
        String siren = nouveauSiren();
        SubscriptionRequest req = demandeEnAttente(siren);
        CustomUserDetails admin = compte("ADMIN");

        mockMvc.perform(post("/intra/registrations/{id}/approve", req.getId()).with(user(admin)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/intra/registrations/{id}/reject", req.getId()).with(user(admin)))
                .andExpect(status().isForbidden());

        assertThat(lignesPartenaire(siren)).isZero();
        assertThat(statut(req.getId())).isEqualTo(SubscriptionRequestStatus.PENDING);
    }

    @Test
    void adminAvecJeton_approuve_etLignePartenaireEcrite() throws Exception {
        String siren = nouveauSiren();
        SubscriptionRequest req = demandeEnAttente(siren);

        mockMvc.perform(post("/intra/registrations/{id}/approve", req.getId())
                        .with(user(compte("ADMIN"))).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(lignesPartenaire(siren)).isEqualTo(1);
        assertThat(statut(req.getId())).isEqualTo(SubscriptionRequestStatus.APPROVED);
    }

    @Test
    void adminAvecJeton_refuse() throws Exception {
        SubscriptionRequest req = demandeEnAttente(nouveauSiren());

        mockMvc.perform(post("/intra/registrations/{id}/reject", req.getId())
                        .with(user(compte("ADMIN"))).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(statut(req.getId())).isEqualTo(SubscriptionRequestStatus.REJECTED);
    }

    // ── Liste ────────────────────────────────────────────────────────────

    @Test
    void liste_afficheSirenEtTva_etChaqueFormulairePorteUnJeton() throws Exception {
        String siren = nouveauSiren();
        demandeEnAttente(siren);

        String html = mockMvc.perform(get("/intra/registrations").with(user(compte("ADMIN"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains(siren).contains(NumerosSociete.tvaFrancaise(siren));
        int formulaires = compter(FORMULAIRE_POST, html);
        assertThat(formulaires).isGreaterThan(0);
        assertThat(compter(Pattern.compile("name=\"_csrf\""), html)).isGreaterThanOrEqualTo(formulaires);
    }

    private static int compter(Pattern motif, String texte) {
        Matcher m = motif.matcher(texte);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }
}
