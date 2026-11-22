package com.logicielpourtous.site.features.theme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.theme.ThemeService.Origine;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;

/**
 * Ecran d'administration « Apparence » sur la vraie chaine de securite web. Tout est ecrit dans une transaction
 * annulee en fin de test : la table des reglages et le journal reviennent a l'etat initial.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ApparenceControllerTest {

    private static final List<String> HOSTILES = List.of("red;}body{display:none", "url(javascript:alert(1))", "</style>");

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ThemeService service;
    @Autowired private ThemeReglageRepository reglages;
    @Autowired private ThemeJournalRepository journal;
    @Autowired private JdbcTemplate jdbc;

    private CustomUserDetails admin;

    @BeforeEach
    void preparer() {
        jdbc.update("DELETE FROM theme_reglages");
        admin = compte("ADMIN", true);
    }

    // ---------- (a) acces ----------

    @Test
    void anonyme_estRedirige() throws Exception {
        mockMvc.perform(get("/admin/apparence"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/login")));
    }

    @Test
    void nonAdmin_recoit403() throws Exception {
        mockMvc.perform(get("/admin/apparence").with(user(compte("USER", true)))).andExpect(status().isForbidden());
    }

    @Test
    void admin_voitUnOngletParGroupe() throws Exception {
        String html = mockMvc.perform(get("/admin/apparence").with(user(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (Map.Entry<String, String> g : ThemeValeurs.GROUPES.entrySet()) {
            assertThat(html).contains("data-bs-target=\"#groupe-" + g.getKey() + "\"").contains("id=\"groupe-" + g.getKey() + "\"");
        }
        assertThat(html).contains("Tout réinitialiser").contains("Revenir à la valeur par défaut").contains("hérité");
        assertThat(html).contains("type=\"color\"").contains("type=\"range\"").contains("<select");
        assertThat(html).contains("min=\"12\"").contains("max=\"18\"");
        assertThat(html).contains("hx-post=\"/admin/apparence/apercu\"");
        for (String cle : ThemeValeurs.cles()) {
            assertThat(html).contains("/admin/apparence/" + cle);
        }
    }

    // ---------- (b) CSRF ----------

    private List<MockHttpServletRequestBuilder> tousLesPost() {
        return List.of(
                post("/admin/apparence/couleur-primaire").param("valeur", "#123456"),
                post("/admin/apparence/couleur-primaire/reinitialiser"),
                post("/admin/apparence/reinitialiser"),
                post("/admin/apparence/apercu").param("cle", "couleur-primaire").param("valeur", "#123456"));
    }

    @Test
    void chaquePostSansJeton_estRefuse_etN_ecritRien() throws Exception {
        for (MockHttpServletRequestBuilder req : tousLesPost()) {
            mockMvc.perform(req.with(user(admin))).andExpect(status().isForbidden());
        }
        assertThat(reglages.count()).isZero();
        assertThat(lignesJournal()).isZero();
    }

    @Test
    void chaquePostAvecJeton_estTraite() throws Exception {
        for (MockHttpServletRequestBuilder req : tousLesPost()) {
            int s = mockMvc.perform(req.with(user(admin)).with(csrf())).andReturn().getResponse().getStatus();
            assertThat(s).isNotEqualTo(403).isLessThan(400);
        }
    }

    @Test
    void postHtmxAnonyme_200AvecHxRedirect() throws Exception {
        mockMvc.perform(post("/admin/apparence/couleur-primaire").header("HX-Request", "true").param("valeur", "#123456"))
                .andExpect(status().isOk()).andExpect(header().string("HX-Redirect", "/login"));
        assertThat(reglages.count()).isZero();
    }

    @Test
    void postNonAdminAvecJeton_403_etN_ecritRien() throws Exception {
        mockMvc.perform(post("/admin/apparence/couleur-primaire").param("valeur", "#123456")
                .with(user(compte("USER", true))).with(csrf())).andExpect(status().isForbidden());
        assertThat(reglages.count()).isZero();
    }

    // ---------- (c) enregistrement valide ----------

    @Test
    void enregistrementValide_estVisibleDansLeCss_etMarqueModifie() throws Exception {
        String avant = css();
        MvcResult r = mockMvc.perform(post("/admin/apparence/couleur-primaire").header("HX-Request", "true")
                .param("valeur", "#1A2B3C").with(user(admin)).with(csrf())).andExpect(status().isOk()).andReturn();
        String corps = r.getResponse().getContentAsString();
        assertThat(corps).contains("modifié").contains("#1a2b3c");
        assertThat(css()).isNotEqualTo(avant).contains("--socle-couleur-primaire:#1a2b3c;");
        assertThat(service.reglages().get("couleur-primaire").origine()).isEqualTo(Origine.MODIFIE);
        assertThat(service.reglages().get("couleur-primaire").valeur()).isEqualTo("#1a2b3c");
    }

    @Test
    void enregistrementSansHtmx_redirigeVersLEcran() throws Exception {
        mockMvc.perform(post("/admin/apparence/arrondi").param("valeur", "10").with(user(admin)).with(csrf()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/admin/apparence")));
        assertThat(service.reglages().get("arrondi").valeur()).isEqualTo("10");
    }

    @Test
    void identifiantJournaliseVientDeLaSession_jamaisDeLaRequete() throws Exception {
        mockMvc.perform(post("/admin/apparence/arrondi").param("valeur", "10").param("adminId", "999")
                .param("modifiePar", "999").with(user(admin)).with(csrf())).andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/admin/apparence/arrondi/reinitialiser").param("adminId", "999")
                .with(user(admin)).with(csrf())).andExpect(status().is3xxRedirection());
        List<Long> ids = jdbc.queryForList("SELECT modifie_par FROM theme_journal WHERE cle = 'arrondi' ORDER BY id", Long.class);
        assertThat(ids).hasSize(2).containsOnly(admin.getUserId());
        assertThat(admin.getUserId()).isNotNull().isNotEqualTo(999L);
    }

    @Test
    void sansIdentifiantDeSession_refuseEtN_ecritRien() throws Exception {
        CustomUserDetails sansId = compte("ADMIN", false);
        mockMvc.perform(post("/admin/apparence/arrondi").param("valeur", "10").with(user(sansId)).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/apparence/reinitialiser").with(user(sansId)).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(reglages.count()).isZero();
        assertThat(lignesJournal()).isZero();
    }

    // ---------- (d) valeurs hostiles a l'enregistrement ----------

    @Test
    void valeurHostile_400GeneriqueSansRienEcrireNiEchoer() throws Exception {
        String cssAvant = css();
        for (String hostile : HOSTILES) {
            for (String cle : List.of("couleur-primaire", "arrondi", "police", "liste-densite")) {
                MvcResult r = mockMvc.perform(post("/admin/apparence/" + cle).param("valeur", hostile)
                        .with(user(admin)).with(csrf())).andExpect(status().isBadRequest()).andReturn();
                assertThat(r.getResponse().getContentAsString()).doesNotContain(hostile).doesNotContain("display:none");
                r = mockMvc.perform(post("/admin/apparence/" + cle).header("HX-Request", "true").param("valeur", hostile)
                        .with(user(admin)).with(csrf())).andExpect(status().isBadRequest()).andReturn();
                assertThat(r.getResponse().getContentAsString()).doesNotContain(hostile);
            }
        }
        assertThat(reglages.count()).isZero();
        assertThat(lignesJournal()).isZero();
        assertThat(css()).isEqualTo(cssAvant);
    }

    @Test
    void valeurAbsente_400() throws Exception {
        mockMvc.perform(post("/admin/apparence/arrondi").with(user(admin)).with(csrf())).andExpect(status().isBadRequest());
        assertThat(reglages.count()).isZero();
    }

    @Test
    void cleInconnue_refuseeSansEchoNiEcriture() throws Exception {
        String hostile = "<script>alert(1)</script>";
        MvcResult r = mockMvc.perform(post("/admin/apparence/{cle}", hostile).param("valeur", "#123456")
                .with(user(admin)).with(csrf())).andReturn();
        assertThat(r.getResponse().getStatus()).isIn(400, 404);
        assertThat(r.getResponse().getContentAsString()).doesNotContain("<script>alert");
        MvcResult r2 = mockMvc.perform(post("/admin/apparence/inconnue").param("valeur", "#123456")
                .with(user(admin)).with(csrf())).andReturn();
        assertThat(r2.getResponse().getStatus()).isIn(400, 404);
        assertThat(r2.getResponse().getContentAsString()).doesNotContain("inconnue");
        MvcResult r3 = mockMvc.perform(post("/admin/apparence/inconnue/reinitialiser")
                .with(user(admin)).with(csrf())).andReturn();
        assertThat(r3.getResponse().getStatus()).isIn(400, 404);
        assertThat(reglages.count()).isZero();
        assertThat(lignesJournal()).isZero();
    }

    // ---------- (e) apercu ----------

    @Test
    void apercuAvecValeurHostile_aucunStyleNiEcho() throws Exception {
        for (String hostile : HOSTILES) {
            MvcResult r = mockMvc.perform(post("/admin/apparence/apercu").param("cle", "couleur-primaire")
                    .param("valeur", hostile).with(user(admin)).with(csrf())).andReturn();
            String corps = r.getResponse().getContentAsString();
            assertThat(r.getResponse().getStatus()).isLessThan(500);
            assertThat(corps).doesNotContain(hostile).doesNotContain("display:none").doesNotContain("--socle-")
                    .doesNotContain("style=").contains("Aperçu indisponible");
        }
        MvcResult inconnue = mockMvc.perform(post("/admin/apparence/apercu").param("cle", "x<b>").param("valeur", "1")
                .with(user(admin)).with(csrf())).andReturn();
        assertThat(inconnue.getResponse().getContentAsString()).doesNotContain("x<b>").doesNotContain("style=");
        assertThat(reglages.count()).isZero();
        assertThat(lignesJournal()).isZero();
    }

    @Test
    void apercuInegal_cleEtValeurNonAppariees_pasDApercu() throws Exception {
        MvcResult r = mockMvc.perform(post("/admin/apparence/apercu").param("cle", "arrondi").param("cle", "police")
                .param("valeur", "10").with(user(admin)).with(csrf())).andReturn();
        assertThat(r.getResponse().getContentAsString()).doesNotContain("style=").contains("Aperçu indisponible");
    }

    @Test
    void apercuValide_poseLesVariablesNormaliseesEtNePersisteRien() throws Exception {
        String cssAvant = css();
        MvcResult r = mockMvc.perform(post("/admin/apparence/apercu").header("HX-Request", "true")
                .param("cle", "couleur-primaire").param("valeur", "#ABCDEF")
                .param("cle", "arrondi").param("valeur", "7")
                .param("cle", "liste-densite").param("valeur", "aere")
                .with(user(admin)).with(csrf())).andExpect(status().isOk()).andReturn();
        String corps = r.getResponse().getContentAsString();
        assertThat(corps).contains("--socle-couleur-primaire:#abcdef").contains("--socle-arrondi:7px")
                .contains("--socle-liste-cellule-padding:.9rem 1rem").doesNotContain("#ABCDEF");
        assertThat(corps).doesNotContain("<html").doesNotContain("<script");
        assertThat(reglages.count()).isZero();
        assertThat(lignesJournal()).isZero();
        assertThat(css()).isEqualTo(cssAvant);
    }

    // ---------- (f) reinitialisation ----------

    @Test
    void reinitialiserUnReglage_puisTout() throws Exception {
        String cssDefaut = css();
        service.definir(admin.getUserId(), "couleur-primaire", "#111111");
        service.definir(admin.getUserId(), "arrondi", "3");
        assertThat(css()).isNotEqualTo(cssDefaut);

        MvcResult r = mockMvc.perform(post("/admin/apparence/couleur-primaire/reinitialiser").header("HX-Request", "true")
                .with(user(admin)).with(csrf())).andExpect(status().isOk()).andReturn();
        assertThat(r.getResponse().getContentAsString()).contains("id=\"groupe-contenu-general\"");
        assertThat(service.reglages().get("couleur-primaire").origine()).isEqualTo(Origine.HERITE);
        assertThat(service.reglages().get("arrondi").origine()).isEqualTo(Origine.MODIFIE);

        mockMvc.perform(post("/admin/apparence/reinitialiser").with(user(admin)).with(csrf()))
                .andExpect(status().isFound());
        assertThat(reglages.count()).isZero();
        assertThat(css()).isEqualTo(cssDefaut);
    }

    // ---------- (g) echappement ----------

    @Test
    void pageEchappeLeTexteAffiche() throws Exception {
        String html = mockMvc.perform(get("/admin/apparence").with(user(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).doesNotContain("th:text").doesNotContain("th:value").doesNotContain("${");
        // Aucun script en ligne : seuls les scripts du layout (src=...) sont presents.
        assertThat(html.replaceAll("(?s)<script[^>]*\\bsrc=[^>]*></script>", "")).doesNotContain("<script");
        // Aucun libelle brut contenant du balisage.
        assertThat(html).doesNotContain("<b>").doesNotContain("onerror=");
        // Les valeurs sont posees en attribut 'value' echappe (ici : texte courant, jamais du HTML libre).
        assertThat(html).contains("value=\"#");
    }

    // ---------- utilitaires ----------

    private String css() throws Exception {
        return mockMvc.perform(get("/css/theme.css")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private long lignesJournal() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM theme_journal WHERE modifie_par = ?", Long.class,
                admin.getUserId());
        return n == null ? 0 : n;
    }

    private CustomUserDetails compte(String role, boolean persiste) {
        Tenant tenant = new Tenant();
        tenant.setName("Test Tenant");
        tenant.setSchemaName("test_tenant_" + UUID.randomUUID());
        tenant = tenantRepository.save(tenant);

        User u = new User();
        u.setEmail("apparence-test-" + UUID.randomUUID() + "@logicielpourtous.com");
        u.setPassword("test");
        u.setFullName("Test " + role);
        u.setActive(true);
        u.setTenant(tenant);
        if (!persiste) {
            u.setRoles(Set.of(new Role(role, role, "ESN")));
            return new CustomUserDetails(u);
        }
        // Ligne reelle (identifiant reel) ; la copie portant le role n'est jamais persistee.
        Long id = userRepository.saveAndFlush(u).getId();
        User copie = new User();
        org.springframework.test.util.ReflectionTestUtils.setField(copie, "id", id);
        copie.setEmail(u.getEmail());
        copie.setPassword("test");
        copie.setFullName(u.getFullName());
        copie.setActive(true);
        copie.setTenant(tenant);
        copie.setRoles(Set.of(new Role(role, role, "ESN")));
        return new CustomUserDetails(copie);
    }
}
