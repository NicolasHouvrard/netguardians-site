package com.logicielpourtous.site.features.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.identite.IdentiteSociete;
import com.logicielpourtous.site.features.identite.NumerosSociete;
import com.logicielpourtous.site.features.identite.RegistreSocietes;
import com.logicielpourtous.site.features.identite.SocieteIdentite;
import com.logicielpourtous.site.features.identite.TypeSociete;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.user.User;

/**
 * Formulaire d'inscription client (champs d'identite), page neutre « societe connue » et tableau de bord des
 * demandes du partenaire proprietaire. Sans {@code @Transactional} (les inscriptions ouvrent leurs propres
 * transactions) : donnees aleatoires, nettoyees apres coup.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InscriptionFormulaireTest {

    private static final String PHRASE_NEUTRE =
            "Cette société est déjà connue de la plateforme. Une demande a été transmise à son responsable.";

    @Autowired private MockMvc mockMvc;
    @Autowired private RegistreSocietes registre;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private DemandeSocieteConnueRepository demandes;
    @Autowired private JdbcTemplate jdbc;

    private final List<String> sirens = new ArrayList<>();
    private final List<String> tvas = new ArrayList<>();
    private final List<String> emails = new ArrayList<>();
    private final List<Long> tenantsCrees = new ArrayList<>();

    private Tenant maitre;
    private Tenant partenaireA;
    private Tenant partenaireB;

    @BeforeEach
    void tenants() {
        // Le Maitre est le tenant de plus petit id : cree en premier, il l'est meme sur une base vide.
        Tenant premier = nouveauTenant("Maitre test");
        maitre = tenantRepository.findAll().stream().min(Comparator.comparing(Tenant::getId)).orElseThrow();
        partenaireA = nouveauTenant("Partenaire A");
        partenaireB = nouveauTenant("Partenaire B");
        assertThat(premier.getId()).isNotNull();
    }

    private Tenant nouveauTenant(String nom) {
        Tenant t = new Tenant();
        t.setName(nom);
        t.setSchemaName("test_formulaire_" + UUID.randomUUID());
        t = tenantRepository.save(t);
        tenantsCrees.add(t.getId());
        return t;
    }

    // ── Jeux de donnees ──────────────────────────────────────────────────

    private String nouveauSiren() {
        while (true) {
            String base = String.format("%08d", ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999));
            for (int cle = 0; cle <= 9; cle++) {
                String siren = base + cle;
                if (NumerosSociete.sirenValide(siren) && !siren.startsWith("356000000")) {
                    sirens.add(siren);
                    tvas.add(NumerosSociete.tvaFrancaise(siren));
                    return siren;
                }
            }
        }
    }

    private SocieteIdentite auRegistre(String siren, Long proprietaire) {
        return registre.enregistrer(TypeSociete.CLIENT,
                IdentiteSociete.depuis(siren, null, NumerosSociete.tvaFrancaise(siren)), proprietaire);
    }

    private DemandeSocieteConnue demande(SocieteIdentite societe, String email, String nom) {
        DemandeSocieteConnue d = new DemandeSocieteConnue();
        d.setSocieteIdentiteId(societe.getId());
        d.setEmailDemandeur(email);
        d.setNomDemandeur(nom);
        return demandes.save(d);
    }

    private MockHttpServletRequestBuilder inscription(String siren, String tva) {
        String email = "formulaire-" + UUID.randomUUID() + "@example.com";
        emails.add(email);
        return post("/register")
                .param("accountType", "CLIENT")
                .param("companyName", "Societe Test")
                .param("fullName", "Alice Martin")
                .param("email", email)
                .param("password", "MotDePasse!1")
                .param("siren", siren)
                .param("tva", tva);
    }

    private CustomUserDetails compte(Tenant tenant, String role) {
        User u = new User();
        u.setEmail("formulaire-" + UUID.randomUUID() + "@example.com");
        u.setPassword("x");
        u.setFullName("Responsable");
        u.setActive(true);
        u.setTenant(tenant);
        u.setRoles(Set.of(new Role(role, role, "ESN")));
        ReflectionTestUtils.setField(u, "id", -1L); // jamais persiste : evite findById(null) sur l'accueil
        return new CustomUserDetails(u);
    }

    // ── Formulaire ───────────────────────────────────────────────────────

    @Test
    void formulaire_proposeSirenSiretEtTva() throws Exception {
        String html = mockMvc.perform(get("/register")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("name=\"siren\"").contains("name=\"tva\"")
                .contains("SIREN ou SIRET").contains("N° de TVA intracommunautaire");
    }

    @Test
    void societeLibre_redirigeVersEmailSent() throws Exception {
        String siren = nouveauSiren();
        String location = mockMvc.perform(inscription(siren, NumerosSociete.tvaFrancaise(siren)))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
        assertThat(location).startsWith("/register/email-sent");
    }

    @Test
    void societeRejouee_redirigeVersPageNeutre() throws Exception {
        String siren = nouveauSiren();
        String tva = NumerosSociete.tvaFrancaise(siren);
        mockMvc.perform(inscription(siren, tva)).andExpect(status().is3xxRedirection());
        mockMvc.perform(inscription(siren, tva))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .redirectedUrl("/register/societe-connue"));
    }

    @Test
    void pageNeutre_phraseExacte_etCorpsIdentiquePourInscriteEtSuivie() throws Exception {
        String inscrite = nouveauSiren();
        String suivie = nouveauSiren();
        auRegistre(inscrite, null);
        auRegistre(suivie, partenaireA.getId());

        MvcResult r1 = mockMvc.perform(inscription(inscrite, NumerosSociete.tvaFrancaise(inscrite)))
                .andExpect(status().isFound()).andReturn();
        MvcResult r2 = mockMvc.perform(inscription(suivie, NumerosSociete.tvaFrancaise(suivie)))
                .andExpect(status().isFound()).andReturn();
        assertThat(r1.getResponse().getRedirectedUrl()).isEqualTo(r2.getResponse().getRedirectedUrl());

        String corps1 = mockMvc.perform(get(r1.getResponse().getRedirectedUrl())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String corps2 = mockMvc.perform(get(r2.getResponse().getRedirectedUrl())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(corps1).isEqualTo(corps2).contains(PHRASE_NEUTRE)
                .doesNotContain(partenaireA.getName()).doesNotContain(inscrite);
    }

    @Test
    void numerosInvalides_retourAuFormulaireAvecLeChampFautif() throws Exception {
        mockMvc.perform(inscription("123456789", "FR00123456789"))
                .andExpect(status().isOk())
                .andExpect(view().name("feature/auth/register"))
                .andExpect(model().attributeExists("error", "champErreur"));
    }

    @Test
    void nomTropLong_retourAuFormulaireAvecLeChampFautif() throws Exception {
        String siren = nouveauSiren();
        mockMvc.perform(inscription(siren, NumerosSociete.tvaFrancaise(siren)).param("companyName", "x".repeat(256)))
                .andExpect(status().isOk())
                .andExpect(view().name("feature/auth/register"))
                .andExpect(model().attribute("champErreur", "companyName"));
        mockMvc.perform(inscription(siren, NumerosSociete.tvaFrancaise(siren)).param("fullName", "x".repeat(256)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("champErreur", "fullName"));
    }

    // ── Tableau de bord des demandes ─────────────────────────────────────

    @Test
    void tableauDeBord_chaquePartenaireNeVoitQueSesSocietes() throws Exception {
        SocieteIdentite deA = auRegistre(nouveauSiren(), partenaireA.getId());
        SocieteIdentite deB = auRegistre(nouveauSiren(), partenaireB.getId());
        SocieteIdentite duMaitre = auRegistre(nouveauSiren(), null);
        demande(deA, "pour-a@example.com", "Demandeur A");
        demande(deB, "pour-b@example.com", "Demandeur B");
        demande(duMaitre, "pour-maitre@example.com", "Demandeur M");

        String pageA = lister(compte(partenaireA, "ADMIN"));
        assertThat(pageA).contains("pour-a@example.com").doesNotContain("pour-b@example.com")
                .doesNotContain("pour-maitre@example.com");
        String pageM = lister(compte(maitre, "ADMIN"));
        assertThat(pageM).contains("pour-maitre@example.com").doesNotContain("pour-a@example.com")
                .doesNotContain("pour-b@example.com");
    }

    @Test
    void tableauDeBord_valeursSaisiesEchappees() throws Exception {
        SocieteIdentite deA = auRegistre(nouveauSiren(), partenaireA.getId());
        demande(deA, "xss@example.com", "<script>alert(1)</script>");
        String page = lister(compte(partenaireA, "ADMIN"));
        assertThat(page).doesNotContain("<script>alert(1)</script>").contains("&lt;script&gt;");
    }

    @Test
    void marquerTraitee_retireDeLaListe_etHorsPerimetreDonne404() throws Exception {
        SocieteIdentite deA = auRegistre(nouveauSiren(), partenaireA.getId());
        DemandeSocieteConnue d = demande(deA, "traiter@example.com", "Demandeur");

        // Le partenaire B ne peut ni la marquer (404, pas 403) ni la lire
        mockMvc.perform(post("/intra/demandes-societe-connue/" + d.getId() + "/traitee")
                        .with(user(compte(partenaireB, "ADMIN"))).with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(demandes.findById(d.getId()).orElseThrow().isTraitee()).isFalse();
        // Id inexistant : meme reponse
        mockMvc.perform(post("/intra/demandes-societe-connue/-5/traitee")
                        .with(user(compte(partenaireA, "ADMIN"))).with(csrf()))
                .andExpect(status().isNotFound());

        // Sans jeton CSRF : refuse
        mockMvc.perform(post("/intra/demandes-societe-connue/" + d.getId() + "/traitee")
                        .with(user(compte(partenaireA, "ADMIN"))))
                .andExpect(status().isForbidden());

        // Le proprietaire la marque : elle disparait de la liste
        mockMvc.perform(post("/intra/demandes-societe-connue/" + d.getId() + "/traitee")
                        .with(user(compte(partenaireA, "ADMIN"))).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(demandes.findById(d.getId()).orElseThrow().isTraitee()).isTrue();
        assertThat(lister(compte(partenaireA, "ADMIN"))).doesNotContain("traiter@example.com");
    }

    @Test
    void marquerTraitee_maitreSurSociete_duMaitreSeulement() throws Exception {
        SocieteIdentite deA = auRegistre(nouveauSiren(), partenaireA.getId());
        SocieteIdentite duMaitre = auRegistre(nouveauSiren(), null);
        DemandeSocieteConnue dA = demande(deA, "a2@example.com", "A");
        DemandeSocieteConnue dM = demande(duMaitre, "m2@example.com", "M");

        mockMvc.perform(post("/intra/demandes-societe-connue/" + dA.getId() + "/traitee")
                        .with(user(compte(maitre, "ADMIN"))).with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/intra/demandes-societe-connue/" + dM.getId() + "/traitee")
                        .with(user(compte(maitre, "ADMIN"))).with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void ecran_reserveAuxAdministrateurs() throws Exception {
        mockMvc.perform(get("/intra/demandes-societe-connue").with(user(compte(partenaireA, "COMPTABLE"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void accueilIntra_lienEtCompteurDesDemandesNonTraitees() throws Exception {
        SocieteIdentite deA = auRegistre(nouveauSiren(), partenaireA.getId());
        demande(deA, "c1@example.com", "C1");
        demande(deA, "c2@example.com", "C2");
        String html = mockMvc.perform(get("/intra").with(user(compte(partenaireA, "ADMIN"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("/intra/demandes-societe-connue").contains("Demandes de société connue")
                .containsPattern("data-compteur-demandes=\"2\"");
    }

    private String lister(CustomUserDetails compte) throws Exception {
        return mockMvc.perform(get("/intra/demandes-societe-connue").with(user(compte)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @AfterEach
    void nettoyer() {
        for (String email : emails) {
            List<Long> userIds = jdbc.queryForList("SELECT id FROM users WHERE email = ?", Long.class, email);
            for (Long userId : userIds) {
                jdbc.update("DELETE FROM appartenances_roles WHERE appartenance_id IN "
                        + "(SELECT id FROM appartenances WHERE user_id = ?)", userId);
                jdbc.update("DELETE FROM appartenances WHERE user_id = ?", userId);
                jdbc.update("DELETE FROM users_roles WHERE user_id = ?", userId);
                jdbc.update("UPDATE contacts SET userid = NULL WHERE userid = ?", userId);
                jdbc.update("DELETE FROM users WHERE id = ?", userId);
            }
        }
        for (String siren : sirens) {
            List<Long> customerIds = jdbc.queryForList("SELECT id FROM customers WHERE siren = ?", Long.class, siren);
            for (Long customerId : customerIds) {
                jdbc.update("DELETE FROM contacts WHERE customer_id = ?", customerId);
                jdbc.update("DELETE FROM sites WHERE customer_id = ?", customerId);
                jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
            }
        }
        for (String tva : tvas) {
            jdbc.update("DELETE FROM demandes_societe_connue WHERE societe_identite_id IN "
                    + "(SELECT id FROM societes_identites WHERE tva = ?)", tva);
            jdbc.update("DELETE FROM societes_identites WHERE tva = ?", tva);
        }
        for (Long id : tenantsCrees) {
            jdbc.update("DELETE FROM tenants WHERE id = ?", id);
        }
    }
}
