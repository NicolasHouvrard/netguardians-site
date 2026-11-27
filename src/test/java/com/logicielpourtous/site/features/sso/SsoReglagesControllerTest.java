package com.logicielpourtous.site.features.sso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.logicielpourtous.site.features.auth.ConfigurationCompteUniqueImpl;
import com.logicielpourtous.site.features.auth.ConfigurationCompteUniqueImpl.ReglagesModifies;
import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.ServerRoleProperties;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.user.User;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/** Ecran de reglages du compte unique. Adresses factices, secrets factices, aucun appel reseau. */
@SpringBootTest
@AutoConfigureMockMvc
class SsoReglagesControllerTest {

    private static final String SECRET = "secret-de-test-non-reel-0123456789";

    @Autowired private MockMvc mockMvc;
    @Autowired private ConfigurationCompteUniqueImpl config;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private ServerRoleProperties serverRole;

    private ListAppender<ILoggingEvent> journal;
    private Tenant tenant;

    @BeforeEach
    void preparer() {
        journal = new ListAppender<>();
        journal.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(journal);
        tenant = new Tenant();
        tenant.setName("Test Tenant");
        tenant.setSchemaName("test_tenant_" + UUID.randomUUID());
        tenant = tenantRepository.save(tenant);
    }

    @AfterEach
    void nettoyer() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(journal);
        jdbc.update("DELETE FROM public.reglages_compte_unique WHERE id = 1");
        config.invalider();
        tenantRepository.deleteById(tenant.getId());
    }

    private CustomUserDetails compte(String role) {
        User u = new User();
        u.setEmail("sso-test-" + UUID.randomUUID() + "@logicielpourtous.com");
        u.setPassword("test");
        u.setFullName("Test " + role);
        u.setActive(true);
        u.setTenant(tenant);
        u.setRoles(Set.of(new Role(role, role, "ESN")));
        return new CustomUserDetails(u);
    }

    private String logs() {
        return journal.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
    }

    private void preparerCompte() {
        config.enregistrer(new ReglagesModifies("https://auth.example.org", "site-test", null,
                "https://site.example.org", null, null), "test");
    }

    private int lignes() {
        return jdbc.queryForObject("SELECT count(*) FROM public.reglages_compte_unique", Integer.class);
    }

    /** POST de generation (doit rediriger) puis affichage de la page avec l'attribut flash, comme un navigateur. */
    private MvcResult genererEtSuivre() throws Exception {
        MvcResult post = mockMvc.perform(post("/admin/sso-config/generer-secret").with(user(compte("ADMIN"))).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/sso-config")).andReturn();
        assertThat(post.getResponse().getContentAsString()).doesNotContain("$pbkdf2");
        return mockMvc.perform(get("/admin/sso-config").with(user(compte("ADMIN")))
                .flashAttrs(post.getFlashMap())).andExpect(status().isOk()).andReturn();
    }

    @Test
    void le_secret_clair_n_est_jamais_dans_la_reponse_ni_le_journal() throws Exception {
        preparerCompte();

        MvcResult post = mockMvc.perform(post("/admin/sso-config/generer-secret").with(user(compte("ADMIN"))).with(csrf()))
                .andExpect(status().is3xxRedirection()).andReturn();
        String corps = mockMvc.perform(get("/admin/sso-config").with(user(compte("ADMIN"))).flashAttrs(post.getFlashMap()))
                .andReturn().getResponse().getContentAsString();

        String secret = config.clientSecret();
        assertThat(secret).hasSize(72);
        assertThat(corps).doesNotContain(secret);
        assertThat(post.getFlashMap().toString()).doesNotContain(secret);
        assertThat(logs()).doesNotContain(secret).contains("secret client genere");
        assertThat(jdbc.queryForObject("SELECT client_secret FROM public.reglages_compte_unique WHERE id = 1",
                String.class)).doesNotContain(secret);
    }

    @Test
    void le_bloc_authelia_contient_client_id_empreinte_et_redirect_uri_calculee() throws Exception {
        preparerCompte();

        MvcResult res = genererEtSuivre();
        String corps = res.getResponse().getContentAsString();
        assertThat(res.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(res.getResponse().getHeader("Pragma")).isEqualTo("no-cache");
        assertThat(res.getResponse().getHeader("Expires")).isEqualTo("0");

        assertThat(corps).contains("client_id: &#39;site-test&#39;").contains("$pbkdf2-sha512$310000$")
                .contains("redirect_uris: [&#39;https://site.example.org/login/oauth2/code/authelia&#39;]")
                .contains("require_pkce: true").contains("pkce_challenge_method: &#39;S256&#39;")
                .contains("client_secret_basic").contains("authelia crypto hash validate");
        Matcher m = Pattern.compile("client_secret: &#39;(\\$pbkdf2-sha512[^&]+)&#39;").matcher(corps);
        assertThat(m.find()).isTrue();
        assertThat(EmpreinteAuthelia.verifie(config.clientSecret(), m.group(1))).isTrue();
    }

    @Test
    void recharger_la_page_ne_regenere_rien_et_n_affiche_plus_le_bloc() throws Exception {
        preparerCompte();
        genererEtSuivre();
        String secretApres = config.clientSecret();
        String version = jdbc.queryForObject("SELECT version::text FROM public.reglages_compte_unique WHERE id = 1",
                String.class);

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/admin/sso-config").with(user(compte("ADMIN"))))
                    .andExpect(status().isOk()).andExpect(content().string(not(containsString("$pbkdf2-sha512"))));
        }
        assertThat(config.clientSecret()).isEqualTo(secretApres);
        assertThat(jdbc.queryForObject("SELECT version::text FROM public.reglages_compte_unique WHERE id = 1",
                String.class)).isEqualTo(version);
    }

    @Test
    void le_bloc_normalise_l_adresse_publique_sans_double_barre() {
        for (String url : new String[] { "https://site.example.org/", "https://site.example.org//",
                "https://site.example.org" }) {
            String bloc = SsoStatusController.blocAuthelia("site-test", "$pbkdf2-sha512$310000$a$b", url);
            assertThat(bloc).contains("'https://site.example.org/login/oauth2/code/authelia'").doesNotContain("org//");
        }
    }

    @Test
    void la_generation_exige_un_identifiant_et_une_adresse_publique() throws Exception {
        MvcResult post = mockMvc.perform(post("/admin/sso-config/generer-secret").with(user(compte("ADMIN"))).with(csrf()))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertThat(post.getFlashMap().get("erreurGlobale").toString()).contains("Renseignez");
        mockMvc.perform(get("/admin/sso-config").with(user(compte("ADMIN"))).flashAttrs(post.getFlashMap()))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString("$pbkdf2-sha512"))));
        assertThat(lignes()).isZero();
    }

    @Test
    void les_actions_destructrices_demandent_confirmation_et_le_bouton_se_desactive() throws Exception {
        String corps = mockMvc.perform(get("/admin/sso-config").with(user(compte("ADMIN"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Matcher gen = Pattern.compile("<form[^>]*generer-secret[^>]*>").matcher(corps);
        Matcher eff = Pattern.compile("<form[^>]*effacer-secret[^>]*>").matcher(corps);
        assertThat(gen.find()).isTrue();
        assertThat(gen.group()).contains("onsubmit=").contains("confirm(").contains("disabled");
        assertThat(eff.find()).isTrue();
        assertThat(eff.group()).contains("onsubmit=").contains("confirm(");
    }

    @Test
    void un_serveur_non_maitre_n_a_pas_le_champ_maitre_et_ignore_une_valeur_forgee() throws Exception {
        serverRole.setRole("PARTNER");
        try {
            String corps = mockMvc.perform(get("/admin/sso-config").with(user(compte("ADMIN"))))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(corps).doesNotContain("name=\"maitreUrlPublique\"");

            mockMvc.perform(post("/admin/sso-config/reglages").with(user(compte("ADMIN"))).with(csrf())
                    .param("issuerUri", "https://auth.example.org").param("clientId", "site-test")
                    .param("urlPublique", "https://site.example.org")
                    .param("maitreUrlPublique", "https://maitre-forge.example.org"))
                    .andExpect(status().is3xxRedirection());
            assertThat(config.lireReglagesEnBase().maitreUrlPublique()).isEmpty();
        } finally {
            serverRole.setRole("MASTER");
        }
    }

    @Test
    void les_messages_sont_accentues() throws Exception {
        MvcResult tester = mockMvc.perform(post("/admin/sso-config/tester").with(user(compte("ADMIN"))).with(csrf()))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertThat(tester.getFlashMap().get("erreurGlobale").toString()).contains("refusée");
        MvcResult regl = mockMvc.perform(post("/admin/sso-config/reglages").with(user(compte("ADMIN"))).with(csrf())
                .param("issuerUri", "https://auth.example.org").param("clientId", "site-test"))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertThat(regl.getFlashMap().get("message").toString()).contains("Réglages enregistrés");
    }

    @Test
    void le_formulaire_refuse_http_et_les_domaines_hors_portail() throws Exception {
        String http = mockMvc.perform(post("/admin/sso-config/reglages").with(user(compte("ADMIN"))).with(csrf())
                .param("issuerUri", "http://auth.example.org").param("clientId", "site-test")
                .param("urlPublique", "https://site.example.org").param("nouveauSecret", SECRET))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(http).contains("doit etre une adresse https").doesNotContain(SECRET);

        String hors = mockMvc.perform(post("/admin/sso-config/reglages").with(user(compte("ADMIN"))).with(csrf())
                .param("issuerUri", "https://auth.example.org").param("clientId", "site-test")
                .param("urlPublique", "https://site.autre-domaine.net"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(hors).contains("domaine parent du portail");

        assertThat(lignes()).isZero();
    }

    @Test
    void le_formulaire_n_affiche_jamais_le_secret_enregistre() throws Exception {
        preparerCompte();
        config.remplacerSecret(SECRET, "test");

        String corps = mockMvc.perform(get("/admin/sso-config").with(user(compte("ADMIN"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(corps).doesNotContain(SECRET).contains("https://auth.example.org").contains("data-secret=\"defini\"");
        assertThat(corps).doesNotContainPattern("type=\"password\"[^>]*value=\"[^\"]");
    }

    @Test
    void seul_un_administrateur_accede_a_l_ecran_et_le_csrf_est_exige() throws Exception {
        mockMvc.perform(get("/admin/sso-config").with(user(compte("USER")))).andExpect(status().isForbidden());
        for (String action : new String[] { "reglages", "tester", "generer-secret", "effacer-secret" }) {
            String url = "/admin/sso-config/" + action;
            mockMvc.perform(post(url).with(user(compte("USER"))).with(csrf())).andExpect(status().isForbidden());
            mockMvc.perform(post(url).with(user(compte("ADMIN")))).andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/admin/sso-config")).andExpect(status().is3xxRedirection());
        assertThat(lignes()).isZero();
    }

    @Test
    void un_secret_vide_dans_le_formulaire_conserve_le_secret_existant() throws Exception {
        preparerCompte();
        config.remplacerSecret(SECRET, "test");

        mockMvc.perform(post("/admin/sso-config/reglages").with(user(compte("ADMIN"))).with(csrf())
                .param("issuerUri", "https://auth.example.org").param("clientId", "site-test2")
                .param("urlPublique", "https://site.example.org").param("retourDeconnexion", "")
                .param("nouveauSecret", ""))
                .andExpect(status().is3xxRedirection());

        assertThat(config.clientSecret()).isEqualTo(SECRET);
        assertThat(config.clientId()).isEqualTo("site-test2");
    }

    @Test
    void le_bouton_effacer_le_secret_n_efface_que_le_secret() throws Exception {
        preparerCompte();
        config.remplacerSecret(SECRET, "test");

        mockMvc.perform(post("/admin/sso-config/effacer-secret").with(user(compte("ADMIN"))).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(config.clientSecret()).isEmpty();
        assertThat(config.clientId()).isEqualTo("site-test");
        assertThat(logs()).contains("secret client efface").doesNotContain(SECRET);
    }

    @Test
    void une_modification_est_journalisee_sans_valeur_secrete() throws Exception {
        CustomUserDetails admin = compte("ADMIN");
        mockMvc.perform(post("/admin/sso-config/reglages").with(user(admin)).with(csrf())
                .param("issuerUri", "https://auth.example.org").param("clientId", "site-test")
                .param("urlPublique", "https://site.example.org").param("nouveauSecret", SECRET))
                .andExpect(status().is3xxRedirection());

        String l = logs();
        assertThat(l).contains("Compte unique : reglages modifies par " + admin.getUsername())
                .contains("issuerUri").contains("clientId").contains("urlPublique").contains("secret");
        assertThat(l).doesNotContain(SECRET);
        assertThat(config.clientSecret()).isEqualTo(SECRET);
    }

    @Test
    void la_page_expose_les_trois_actions_et_le_champ_maitre() throws Exception {
        String corps = mockMvc.perform(get("/admin/sso-config").with(user(compte("ADMIN"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(corps).contains("Compte unique (Authelia)").contains("/admin/sso-config/reglages")
                .contains("/admin/sso-config/tester").contains("/admin/sso-config/generer-secret")
                .contains("name=\"_csrf\"").contains("name=\"maitreUrlPublique\"");
    }
}
