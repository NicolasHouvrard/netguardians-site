package com.logicielpourtous.site.features.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.identite.IdentiteSociete;
import com.logicielpourtous.site.features.identite.NumerosSociete;
import com.logicielpourtous.site.features.identite.RegistreSocietes;
import com.logicielpourtous.site.features.identite.TypeSociete;
import com.logicielpourtous.site.features.registration.SubscriptionRequest;
import com.logicielpourtous.site.features.registration.SubscriptionRequest.SubscriptionRequestStatus;
import com.logicielpourtous.site.features.registration.SubscriptionRequestController;
import com.logicielpourtous.site.features.registration.SubscriptionRequestRepository;

/**
 * Inscription d'un partenaire (ESN) : identite obligatoire, refus neutre d'une societe deja connue,
 * registre ecrit seulement a l'approbation (et dans la meme transaction que la creation de la fiche).
 * Sans {@code @Transactional} : on observe ce qui est reellement valide en base ; nettoyage explicite.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InscriptionEsnTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private RegistreSocietes registre;
    @Autowired private SubscriptionRequestRepository requetes;
    @Autowired private SubscriptionRequestController approbation;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantDuTest;
    private final List<String> emails = new ArrayList<>();
    private final List<String> sirens = new ArrayList<>();
    private final List<String> tvas = new ArrayList<>();

    @BeforeEach
    void tenantDisponible() {
        if (tenantRepository.count() == 0) {
            Tenant tenant = new Tenant();
            tenant.setName("Tenant inscription esn test");
            tenant.setSchemaName("test_esn_" + UUID.randomUUID());
            tenantDuTest = tenantRepository.save(tenant).getId();
        }
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

    private String nouvelEmail() {
        String email = "esn-" + UUID.randomUUID() + "@example.com";
        emails.add(email);
        return email;
    }

    private MockHttpServletRequestBuilder inscription(String email, String siren, String tva) {
        return post("/register")
                .param("accountType", "ESN")
                .param("companyName", "ESN Test")
                .param("fullName", "Alice Martin")
                .param("email", email)
                .param("phone", "0600000000")
                .param("plan", "STANDARD")
                .param("siren", siren)
                .param("tva", tva);
    }

    private long nbRegistre(String siren) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM societes_identites WHERE siren = ?", Long.class, siren);
    }

    private long nbDemandesEsn(String email) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM subscription_requests WHERE email = ?", Long.class, email);
    }

    private long nbDemandesSocieteConnue(String email) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM demandes_societe_connue WHERE email_demandeur = ?",
                Long.class, email);
    }

    private SubscriptionRequest requeteDe(String email) {
        return requetes.findAll().stream().filter(r -> email.equals(r.getEmail())).findFirst().orElseThrow();
    }

    private RedirectAttributesModelMap approuver(Long id) {
        RedirectAttributesModelMap ra = new RedirectAttributesModelMap();
        enAdmin(() -> approbation.approve(id, null, ra));
        return ra;
    }

    /** Appel direct du controleur (reserve aux administrateurs) sous une identite ADMIN. */
    private static void enAdmin(Runnable appel) {
        SecurityContext precedent = SecurityContextHolder.getContext();
        SecurityContext admin = SecurityContextHolder.createEmptyContext();
        admin.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("admin-test", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        SecurityContextHolder.setContext(admin);
        try {
            appel.run();
        } finally {
            SecurityContextHolder.setContext(precedent);
        }
    }

    // ── Soumission ───────────────────────────────────────────────────────

    @Test
    void demandeValide_stockeLesNumerosNormalises_etNEcritPasLeRegistre() throws Exception {
        String siren = nouveauSiren();
        String email = nouvelEmail();

        mockMvc.perform(inscription(email, siren.substring(0, 3) + " " + siren.substring(3),
                        NumerosSociete.tvaFrancaise(siren).toLowerCase()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/register/confirm"));

        SubscriptionRequest req = requeteDe(email);
        assertThat(req.getSiren()).isEqualTo(siren);
        assertThat(req.getVatNumber()).isEqualTo(NumerosSociete.tvaFrancaise(siren));
        assertThat(req.getStatus()).isEqualTo(SubscriptionRequestStatus.PENDING);
        // Une demande en attente ne reserve pas la societe
        assertThat(nbRegistre(siren)).isZero();
    }

    @Test
    void societeDejaAuRegistre_refusNeutre_aucuneDemande_etDemandeSocieteConnueSansDoublon() throws Exception {
        String siren = nouveauSiren();
        String tva = NumerosSociete.tvaFrancaise(siren);
        registre.enregistrer(TypeSociete.CLIENT, IdentiteSociete.depuis(siren, null, tva), 7L);
        String email = nouvelEmail();

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(inscription(email, siren, tva))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl("/register/societe-connue"));
        }

        assertThat(nbDemandesEsn(email)).isZero();
        assertThat(nbDemandesSocieteConnue(email)).isEqualTo(1);
    }

    @Test
    void tvaEtrangere_refusee_retourAuFormulaireAvecChampEtCaptcha() throws Exception {
        String email = nouvelEmail();
        mockMvc.perform(inscription(email, "", "DE123456789"))
                .andExpect(status().isOk())
                .andExpect(view().name("feature/auth/register"))
                .andExpect(model().attribute("champErreur", "tva"))
                .andExpect(model().attribute("activeType", "ESN"))
                .andExpect(model().attributeExists("error", "captchaEnabled", "captchaSiteKey"));
        assertThat(nbDemandesEsn(email)).isZero();
    }

    @Test
    void numeroInvalide_retourAuFormulaireAvecChampFautif() throws Exception {
        String email = nouvelEmail();
        mockMvc.perform(inscription(email, "123456789", "FR00123456789"))
                .andExpect(status().isOk())
                .andExpect(view().name("feature/auth/register"))
                .andExpect(model().attributeExists("champErreur", "captchaEnabled"));
        assertThat(nbDemandesEsn(email)).isZero();
    }

    @Test
    void tvaAbsente_refusee() throws Exception {
        String email = nouvelEmail();
        mockMvc.perform(inscription(email, nouveauSiren(), ""))
                .andExpect(status().isOk())
                .andExpect(model().attribute("champErreur", "tva"));
        assertThat(nbDemandesEsn(email)).isZero();
    }

    @Test
    void champTropLong_refuse() throws Exception {
        String siren = nouveauSiren();
        String email = nouvelEmail();
        mockMvc.perform(inscription(email, siren, NumerosSociete.tvaFrancaise(siren))
                        .param("companyName", "x".repeat(256)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("champErreur", "companyName"))
                .andExpect(model().attribute("activeType", "ESN"));
        assertThat(nbDemandesEsn(email)).isZero();
    }

    @Test
    void emailDejaDemande_messageInchange() throws Exception {
        String siren = nouveauSiren();
        String email = nouvelEmail();
        mockMvc.perform(inscription(email, siren, NumerosSociete.tvaFrancaise(siren)))
                .andExpect(redirectedUrl("/register/confirm"));
        String autre = nouveauSiren();
        mockMvc.perform(inscription(email, autre, NumerosSociete.tvaFrancaise(autre)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("error", "Une demande existe déjà pour cet email."));
    }

    // ── Approbation / rejet ──────────────────────────────────────────────

    @Test
    void approbation_creeLaLignePartenaire_lieeALaFiche() throws Exception {
        String siren = nouveauSiren();
        String email = nouvelEmail();
        mockMvc.perform(inscription(email, siren, NumerosSociete.tvaFrancaise(siren)))
                .andExpect(redirectedUrl("/register/confirm"));
        SubscriptionRequest req = requeteDe(email);

        Map<String, ?> flash = approuver(req.getId()).getFlashAttributes();

        assertThat(flash).containsKey("successMessage");
        assertThat(requetes.findById(req.getId()).orElseThrow().getStatus())
                .isEqualTo(SubscriptionRequestStatus.APPROVED);
        Map<String, Object> ligne = jdbc.queryForMap(
                "SELECT type, proprietaire_tenant_id, fiche_type, fiche_id FROM societes_identites WHERE siren = ?",
                siren);
        assertThat(ligne.get("type")).isEqualTo("PARTENAIRE");
        assertThat(ligne.get("proprietaire_tenant_id")).isNull();
        assertThat(ligne.get("fiche_type")).isEqualTo("customers");
        Long customerId = jdbc.queryForObject("SELECT id FROM customers WHERE email = ?", Long.class, email);
        assertThat(((Number) ligne.get("fiche_id")).longValue()).isEqualTo(customerId);
        assertThat(jdbc.queryForObject("SELECT siren FROM customers WHERE id = ?", String.class, customerId))
                .isEqualTo(siren);
    }

    @Test
    void rejet_neCreeAucuneLigneDeRegistre() throws Exception {
        String siren = nouveauSiren();
        String email = nouvelEmail();
        mockMvc.perform(inscription(email, siren, NumerosSociete.tvaFrancaise(siren)))
                .andExpect(redirectedUrl("/register/confirm"));
        SubscriptionRequest req = requeteDe(email);

        enAdmin(() -> approbation.reject(req.getId(), null, new RedirectAttributesModelMap()));

        assertThat(requetes.findById(req.getId()).orElseThrow().getStatus())
                .isEqualTo(SubscriptionRequestStatus.REJECTED);
        assertThat(nbRegistre(siren)).isZero();
    }

    @Test
    void approbation_societeInscriteEntreTemps_echecClair_demandeResteEnAttente_rienCree() throws Exception {
        String siren = nouveauSiren();
        String tva = NumerosSociete.tvaFrancaise(siren);
        String email = nouvelEmail();
        mockMvc.perform(inscription(email, siren, tva)).andExpect(redirectedUrl("/register/confirm"));
        SubscriptionRequest req = requeteDe(email);
        // Un autre acteur inscrit la societe entre la demande et l'approbation
        registre.enregistrer(TypeSociete.CLIENT, IdentiteSociete.depuis(siren, null, tva), null);

        Map<String, ?> flash = approuver(req.getId()).getFlashAttributes();

        assertThat((String) flash.get("errorMessage")).contains("déjà enregistrée");
        assertThat(flash).doesNotContainKey("successMessage");
        assertThat(requetes.findById(req.getId()).orElseThrow().getStatus())
                .isEqualTo(SubscriptionRequestStatus.PENDING);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customers WHERE email = ?", Long.class, email)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Long.class, email)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contacts WHERE email = ?", Long.class, email)).isZero();
        // La ligne du registre est restee celle de l'autre inscription
        assertThat(jdbc.queryForObject("SELECT type FROM societes_identites WHERE siren = ?", String.class, siren))
                .isEqualTo("CLIENT");
    }

    @Test
    void approbation_ancienneDemandeSansIdentite_approuveeSansRegistre() throws Exception {
        String email = nouvelEmail();
        SubscriptionRequest req = new SubscriptionRequest();
        req.setCompanyName("Ancienne ESN");
        req.setContactName("Bob Ancien");
        req.setEmail(email);
        req = requetes.save(req);

        Map<String, ?> flash = approuver(req.getId()).getFlashAttributes();

        assertThat(flash).containsKey("successMessage");
        assertThat(requetes.findById(req.getId()).orElseThrow().getStatus())
                .isEqualTo(SubscriptionRequestStatus.APPROVED);
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
            List<Long> customerIds = jdbc.queryForList("SELECT id FROM customers WHERE email = ?", Long.class, email);
            for (Long customerId : customerIds) {
                jdbc.update("DELETE FROM contacts WHERE customer_id = ?", customerId);
                jdbc.update("DELETE FROM sites WHERE customer_id = ?", customerId);
                jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
            }
            jdbc.update("DELETE FROM subscription_requests WHERE email = ?", email);
            jdbc.update("DELETE FROM demandes_societe_connue WHERE email_demandeur = ?", email);
        }
        for (String tva : tvas) {
            jdbc.update("DELETE FROM demandes_societe_connue WHERE societe_identite_id IN "
                    + "(SELECT id FROM societes_identites WHERE tva = ?)", tva);
            jdbc.update("DELETE FROM societes_identites WHERE tva = ?", tva);
        }
        if (tenantDuTest != null) {
            jdbc.update("DELETE FROM tenants WHERE id = ?", tenantDuTest);
        }
    }
}
