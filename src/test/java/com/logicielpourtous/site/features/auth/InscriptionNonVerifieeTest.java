package com.logicielpourtous.site.features.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicielpourtous.site.features.auth.InscriptionSocieteService.DemandeClient;
import com.logicielpourtous.site.features.auth.InscriptionSocieteService.ResultatInscription;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.customers.ContactRepository;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.customers.SiteRepository;
import com.logicielpourtous.site.features.identite.IdentiteSociete;
import com.logicielpourtous.site.features.identite.NumerosSociete;
import com.logicielpourtous.site.features.identite.RegistreSocietes;
import com.logicielpourtous.site.features.identite.SocieteIdentite;
import com.logicielpourtous.site.features.identite.SocieteIdentiteRepository;
import com.logicielpourtous.site.features.portal.PortalUserService;
import com.logicielpourtous.site.features.user.AppartenanceRepository;
import com.logicielpourtous.site.features.user.UserRepository;

/**
 * Liberation des inscriptions jamais verifiees : seule une inscription autonome de client, appartenant au Maitre,
 * ancienne de plus du delai (72 h par defaut), dont tous les comptes sont inactifs avec un jeton de verification
 * en attente et que rien d'autre ne reference, est supprimee. Tous les autres cas sont refuses.
 *
 * <p>SANS {@code @Transactional} (le service gere ses transactions) : numeros et e-mails aleatoires, nettoyes apres.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InscriptionNonVerifieeTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private SiteRepository siteRepository;
    @Autowired private ContactRepository contactRepository;
    @Autowired private PortalUserService portalUserService;
    @Autowired private AppartenanceRepository appartenanceRepository;
    @Autowired private DemandeSocieteConnueRepository demandes;

    @Autowired private InscriptionSocieteService service;
    @Autowired private NettoyageInscriptionsNonVerifiees nettoyage;
    @Autowired private SocieteIdentiteRepository identites;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private Long tenantDuTest;
    private final List<String> emails = new ArrayList<>();
    private final List<String> sirens = new ArrayList<>();
    private final List<String> tvas = new ArrayList<>();
    private final List<Long> requetes = new ArrayList<>();

    @BeforeEach
    void tenantDisponible() {
        if (tenantRepository.count() == 0) {
            Tenant tenant = new Tenant();
            tenant.setName("Tenant inscription non verifiee test");
            tenant.setSchemaName("test_inscription_" + UUID.randomUUID());
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
        String email = "non-verifiee-" + UUID.randomUUID() + "@example.com";
        emails.add(email);
        return email;
    }

    private DemandeClient demande(String email, String siren) {
        return new DemandeClient("Societe Test", "Alice Martin", email, "MotDePasse!1", siren, null,
                NumerosSociete.tvaFrancaise(siren));
    }

    /** Inscription reussie ; renvoie la ligne de registre creee. */
    private SocieteIdentite inscrire(String email, String siren) {
        assertThat(service.inscrireClient(demande(email, siren))).isInstanceOf(ResultatInscription.Creee.class);
        return identites.findBySiren(siren).orElseThrow();
    }

    /** Recule la date de l'inscription (registre et comptes) de {@code heures}. */
    private void vieillir(SocieteIdentite s, int heures) {
        jdbc.update("UPDATE societes_identites SET cree_a = now() - make_interval(hours => ?) WHERE id = ?",
                heures, s.getId());
        jdbc.update("UPDATE users SET created_at = created_at - make_interval(hours => ?) WHERE customer_id = ?",
                heures, s.getFicheId());
    }

    /** Photographie de tout ce qui a ete cree pour une inscription. */
    private record Empreinte(long registre, long demandes, long customers, long sites, long contacts, long users,
                             long usersRoles, long appartenances, long appartenancesRoles) {
        boolean vide() {
            return registre + demandes + customers + sites + contacts + users + usersRoles + appartenances
                    + appartenancesRoles == 0;
        }
    }

    private long compter(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private Empreinte empreinte(Long registreId, Long customerId, String email) {
        return new Empreinte(
                compter("SELECT COUNT(*) FROM societes_identites WHERE id = ?", registreId),
                compter("SELECT COUNT(*) FROM demandes_societe_connue WHERE societe_identite_id = ?", registreId),
                compter("SELECT COUNT(*) FROM customers WHERE id = ?", customerId),
                compter("SELECT COUNT(*) FROM sites WHERE customer_id = ?", customerId),
                compter("SELECT COUNT(*) FROM contacts WHERE customer_id = ? OR email = ?", customerId, email),
                compter("SELECT COUNT(*) FROM users WHERE customer_id = ? OR email = ?", customerId, email),
                compter("SELECT COUNT(*) FROM users_roles ur JOIN users u ON u.id = ur.user_id "
                        + "WHERE u.customer_id = ? OR u.email = ?", customerId, email),
                compter("SELECT COUNT(*) FROM appartenances WHERE customer_id = ?", customerId),
                compter("SELECT COUNT(*) FROM appartenances_roles ar JOIN appartenances a ON a.id = ar.appartenance_id "
                        + "WHERE a.customer_id = ?", customerId));
    }

    private Long userIdDe(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private Long requete(Long customerId, Long createurId) {
        Long id = jdbc.queryForObject("INSERT INTO requests (title, status, customer_id, created_by_user_id) "
                + "VALUES ('Demande test liberation', 'NEW', ?, ?) RETURNING id", Long.class, customerId, createurId);
        requetes.add(id);
        return id;
    }

    // ── Liberation pendant une nouvelle inscription ─────────────────────

    @Test
    void ancienneNonVerifiee_nouvelleInscriptionDuMemeSirenReussit_etAncienneEntierementSupprimee() {
        String siren = nouveauSiren();
        String emailAncien = nouvelEmail();
        SocieteIdentite ancienne = inscrire(emailAncien, siren);
        // Une demande « societe connue » rattachee a l'ancienne ligne, supprimee avec elle
        assertThat(service.inscrireClient(demande(nouvelEmail(), siren)))
                .isInstanceOf(ResultatInscription.DejaConnue.class);
        Empreinte avant = empreinte(ancienne.getId(), ancienne.getFicheId(), emailAncien);
        assertThat(avant).isEqualTo(new Empreinte(1, 1, 1, 1, 1, 1, 1, 1, 1));
        vieillir(ancienne, 73);

        String emailNouveau = nouvelEmail();
        ResultatInscription resultat = service.inscrireClient(demande(emailNouveau, siren));

        assertThat(resultat).isInstanceOf(ResultatInscription.Creee.class);
        assertThat(empreinte(ancienne.getId(), ancienne.getFicheId(), emailAncien).vide())
                .as("ancienne inscription entierement supprimee, sans orphelin").isTrue();
        SocieteIdentite nouvelle = identites.findBySiren(siren).orElseThrow();
        assertThat(nouvelle.getId()).isNotEqualTo(ancienne.getId());
        assertThat(nouvelle.getOrigine()).isEqualTo("INSCRIPTION_CLIENT");
        assertThat(empreinte(nouvelle.getId(), nouvelle.getFicheId(), emailNouveau))
                .isEqualTo(new Empreinte(1, 0, 1, 1, 1, 1, 1, 1, 1));
    }

    @Test
    void nonVerifieeDepuisUneHeure_resteDejaConnue_rienSupprime() {
        String siren = nouveauSiren();
        String email = nouvelEmail();
        SocieteIdentite s = inscrire(email, siren);
        vieillir(s, 1);
        Empreinte avant = empreinte(s.getId(), s.getFicheId(), email);

        assertThat(service.inscrireClient(demande(nouvelEmail(), siren)))
                .isInstanceOf(ResultatInscription.DejaConnue.class);

        Empreinte apres = empreinte(s.getId(), s.getFicheId(), email);
        assertThat(apres.registre()).isEqualTo(1);
        assertThat(apres.customers()).isEqualTo(avant.customers());
        assertThat(apres.users()).isEqualTo(avant.users());
        assertThat(apres.appartenances()).isEqualTo(avant.appartenances());
    }

    @Test
    void ancienneMaisUtilisateurActif_resteDejaConnue() {
        String siren = nouveauSiren();
        String email = nouvelEmail();
        SocieteIdentite s = inscrire(email, siren);
        vieillir(s, 200);
        jdbc.update("UPDATE users SET active = TRUE, email_verification_token = NULL WHERE email = ?", email);

        assertThat(service.inscrireClient(demande(nouvelEmail(), siren)))
                .isInstanceOf(ResultatInscription.DejaConnue.class);
        assertThat(empreinte(s.getId(), s.getFicheId(), email).registre()).isEqualTo(1);
        assertThat(compter("SELECT COUNT(*) FROM users WHERE email = ?", email)).isEqualTo(1);
    }

    // ── liberer : cas jamais liberes ─────────────────────────────────────

    @Test
    void liberer_ancienneNonVerifiee_supprimeTout() {
        String email = nouvelEmail();
        SocieteIdentite s = inscrire(email, nouveauSiren());
        vieillir(s, 73);

        assertThat(service.liberer(s)).isTrue();
        assertThat(empreinte(s.getId(), s.getFicheId(), email).vide()).isTrue();
    }

    @Test
    void liberer_utilisateurInactifSansJeton_jamaisLibere() {
        String email = nouvelEmail();
        SocieteIdentite s = inscrire(email, nouveauSiren());
        vieillir(s, 200);
        // Desactive par un administrateur apres verification : ce n'est pas une inscription en attente
        jdbc.update("UPDATE users SET email_verification_token = NULL WHERE email = ?", email);

        assertThat(service.liberer(s)).isFalse();
        assertThat(empreinte(s.getId(), s.getFicheId(), email).registre()).isEqualTo(1);
    }

    @Test
    void liberer_registreDUnPartenaire_jamaisLibere() {
        String email = nouvelEmail();
        SocieteIdentite s = inscrire(email, nouveauSiren());
        vieillir(s, 200);
        jdbc.update("UPDATE societes_identites SET proprietaire_tenant_id = 7 WHERE id = ?", s.getId());

        assertThat(service.liberer(identites.findById(s.getId()).orElseThrow())).isFalse();
        assertThat(empreinte(s.getId(), s.getFicheId(), email).customers()).isEqualTo(1);
    }

    @Test
    void liberer_clientRattacheAUnPartenaire_jamaisLibere() {
        String email = nouvelEmail();
        SocieteIdentite s = inscrire(email, nouveauSiren());
        vieillir(s, 200);
        Long tenant = jdbc.queryForObject("SELECT MIN(id) FROM tenants", Long.class);
        jdbc.update("UPDATE customers SET tenant_id = ? WHERE id = ?", tenant, s.getFicheId());

        assertThat(service.liberer(s)).isFalse();
        assertThat(empreinte(s.getId(), s.getFicheId(), email).customers()).isEqualTo(1);
    }

    @Test
    void liberer_ligneRepriseDeLExistant_jamaisLiberee() {
        String email = nouvelEmail();
        SocieteIdentite s = inscrire(email, nouveauSiren());
        vieillir(s, 200);
        // Une ligne reprise de l'existant n'a pas l'origine « inscription »
        jdbc.update("UPDATE societes_identites SET origine = NULL WHERE id = ?", s.getId());

        assertThat(service.liberer(identites.findById(s.getId()).orElseThrow())).isFalse();
        assertThat(empreinte(s.getId(), s.getFicheId(), email).customers()).isEqualTo(1);
    }

    @Test
    void liberer_clientAvecUneDemande_jamaisLibere() {
        String email = nouvelEmail();
        SocieteIdentite s = inscrire(email, nouveauSiren());
        vieillir(s, 200);
        requete(s.getFicheId(), null);

        assertThat(service.liberer(s)).isFalse();
        assertThat(empreinte(s.getId(), s.getFicheId(), email).customers()).isEqualTo(1);
        assertThat(compter("SELECT COUNT(*) FROM users WHERE email = ?", email)).isEqualTo(1);
    }

    @Test
    void liberer_utilisateurReferenceAilleurs_jamaisLibere() {
        String email = nouvelEmail();
        SocieteIdentite s = inscrire(email, nouveauSiren());
        vieillir(s, 200);
        requete(null, userIdDe(email));

        assertThat(service.liberer(s)).isFalse();
        assertThat(compter("SELECT COUNT(*) FROM users WHERE email = ?", email)).isEqualTo(1);
    }

    @Test
    void liberer_contactDUnAutreClientLieAuCompte_jamaisLibere() {
        String email = nouvelEmail();
        SocieteIdentite s = inscrire(email, nouveauSiren());
        vieillir(s, 200);
        String emailAutre = nouvelEmail();
        SocieteIdentite autre = inscrire(emailAutre, nouveauSiren());
        // Le compte de l'inscription est aussi le compte d'un contact d'un autre client
        jdbc.update("UPDATE contacts SET userid = ? WHERE customer_id = ?", userIdDe(email), autre.getFicheId());

        assertThat(service.liberer(s)).isFalse();
        assertThat(compter("SELECT COUNT(*) FROM users WHERE email = ?", email)).isEqualTo(1);
    }

    @Test
    void liberer_estAtomique_unRetourArriereAnnuleTout() {
        String email = nouvelEmail();
        SocieteIdentite s = inscrire(email, nouveauSiren());
        vieillir(s, 73);
        Empreinte avant = empreinte(s.getId(), s.getFicheId(), email);

        new TransactionTemplate(transactionManager).executeWithoutResult(statut -> {
            assertThat(service.liberer(s)).isTrue();
            statut.setRollbackOnly();
        });

        assertThat(empreinte(s.getId(), s.getFicheId(), email)).isEqualTo(avant);
    }

    // ── Nettoyage quotidien ──────────────────────────────────────────────

    @Test
    void nettoyage_supprimeSeulementLesAnciennesNonVerifiees() {
        String emailAncienne = nouvelEmail();
        SocieteIdentite ancienne = inscrire(emailAncienne, nouveauSiren());
        vieillir(ancienne, 73);
        String emailRecente = nouvelEmail();
        SocieteIdentite recente = inscrire(emailRecente, nouveauSiren());
        vieillir(recente, 1);
        String emailActive = nouvelEmail();
        SocieteIdentite active = inscrire(emailActive, nouveauSiren());
        vieillir(active, 200);
        jdbc.update("UPDATE users SET active = TRUE, email_verification_token = NULL WHERE email = ?", emailActive);
        String emailPartenaire = nouvelEmail();
        SocieteIdentite partenaire = inscrire(emailPartenaire, nouveauSiren());
        vieillir(partenaire, 200);
        jdbc.update("UPDATE societes_identites SET proprietaire_tenant_id = 7 WHERE id = ?", partenaire.getId());

        int liberees = nettoyage.nettoyer();

        assertThat(liberees).isGreaterThanOrEqualTo(1);
        assertThat(empreinte(ancienne.getId(), ancienne.getFicheId(), emailAncienne).vide()).isTrue();
        assertThat(empreinte(recente.getId(), recente.getFicheId(), emailRecente).registre()).isEqualTo(1);
        assertThat(empreinte(active.getId(), active.getFicheId(), emailActive).registre()).isEqualTo(1);
        assertThat(empreinte(partenaire.getId(), partenaire.getFicheId(), emailPartenaire).registre()).isEqualTo(1);
        assertThat(compter("SELECT COUNT(*) FROM users WHERE email IN (?, ?, ?)",
                emailRecente, emailActive, emailPartenaire)).isEqualTo(3);
    }

    // ── Revue 1 : compte verifie puis desactive (I1) ─────────────────────

    private String jetonDe(String email) {
        return jdbc.queryForObject("SELECT email_verification_token FROM users WHERE email = ?", String.class, email);
    }

    private void verifierParLeLien(String email) throws Exception {
        mockMvc.perform(get("/register/verify").param("token", jetonDe(email)))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login?verified=true"));
    }

    @Test
    void compteVerifiePuisDesactiveAvecNouveauJeton_jamaisLibere_niParLeNettoyage() throws Exception {
        String email = nouvelEmail();
        SocieteIdentite s = inscrire(email, nouveauSiren());
        verifierParLeLien(email);
        // Desactive par l'equipe, puis nouveau jeton (renvoi de verification par l'equipe)
        jdbc.update("UPDATE users SET active = FALSE, email_verification_token = ? WHERE email = ?",
                UUID.randomUUID().toString(), email);
        vieillir(s, 200);

        assertThat(service.liberer(s)).isFalse();
        nettoyage.nettoyer();
        assertThat(empreinte(s.getId(), s.getFicheId(), email).registre()).isEqualTo(1);
        assertThat(compter("SELECT COUNT(*) FROM users WHERE email = ?", email)).isEqualTo(1);
    }

    @Test
    void renvoiPublic_pourUnCompteVerifiePuisDesactive_neRegenerePasDeJeton_reponseNeutre() throws Exception {
        String email = nouvelEmail();
        inscrire(email, nouveauSiren());
        verifierParLeLien(email);
        jdbc.update("UPDATE users SET active = FALSE WHERE email = ?", email);

        mockMvc.perform(post("/register/resend").param("email", email))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/register/email-sent?sent&email=*"));

        assertThat(jetonDe(email)).isNull();
    }

    @Test
    void renvoiPublic_pourUnCompteJamaisVerifie_renouvelleLeJeton() throws Exception {
        String email = nouvelEmail();
        inscrire(email, nouveauSiren());
        String avant = jetonDe(email);

        mockMvc.perform(post("/register/resend").param("email", email))
                .andExpect(status().isFound())
                .andExpect(redirectedUrlPattern("/register/email-sent?sent&email=*"));

        assertThat(jetonDe(email)).isNotNull().isNotEqualTo(avant);
    }

    @Test
    void dateDeVerification_videALInscription_renseigneeParLeLien_conserveeApresDesactivation() throws Exception {
        String email = nouvelEmail();
        inscrire(email, nouveauSiren());
        assertThat(compter("SELECT COUNT(*) FROM users WHERE email = ? AND email_verified_at IS NULL", email))
                .isEqualTo(1);

        verifierParLeLien(email);
        jdbc.update("UPDATE users SET active = FALSE WHERE email = ?", email);

        assertThat(compter("SELECT COUNT(*) FROM users WHERE email = ? AND email_verified_at IS NOT NULL", email))
                .isEqualTo(1);
    }

    // ── Revue 1 : compte actif avec un jeton encore present (M3) ─────────

    @Test
    void liberer_compteActifAvecJetonEnAttente_jamaisLibere() {
        String email = nouvelEmail();
        SocieteIdentite s = inscrire(email, nouveauSiren());
        vieillir(s, 200);
        jdbc.update("UPDATE users SET active = TRUE WHERE email = ?", email);
        assertThat(jetonDe(email)).isNotNull();

        assertThat(service.liberer(s)).isFalse();
        assertThat(compter("SELECT COUNT(*) FROM users WHERE email = ?", email)).isEqualTo(1);
    }

    // ── Revue 1 : references sans le nom usuel (M1) ──────────────────────

    @Test
    void liberer_clientAvecUneIntegration_jamaisLibere() {
        String email = nouvelEmail();
        SocieteIdentite s = inscrire(email, nouveauSiren());
        vieillir(s, 200);
        Long id = jdbc.queryForObject("INSERT INTO client_integrations (customerid, enabled, servicetype) "
                + "VALUES (?, FALSE, 'CUSTOM') RETURNING id", Long.class, s.getFicheId());
        try {
            assertThat(service.liberer(s)).isFalse();
            assertThat(empreinte(s.getId(), s.getFicheId(), email).customers()).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM client_integrations WHERE id = ?", id);
        }
    }

    @Test
    void liberer_compteCommercialDUneOpportunite_jamaisLibere() {
        String email = nouvelEmail();
        SocieteIdentite s = inscrire(email, nouveauSiren());
        vieillir(s, 200);
        Long id = jdbc.queryForObject("INSERT INTO crm_opportunities (name, assigned_commercial_id) "
                + "VALUES ('Opportunite test liberation', ?) RETURNING id", Long.class, userIdDe(email));
        try {
            assertThat(service.liberer(s)).isFalse();
            assertThat(compter("SELECT COUNT(*) FROM users WHERE email = ?", email)).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM crm_opportunities WHERE id = ?", id);
        }
    }

    // ── Revue 1 : liberation concurrente pendant une inscription (M2) ────

    @Test
    void ligneLibereeParAilleursPendantLInscription_inscriptionReussit_sansErreurDeCle() {
        String siren = nouveauSiren();
        String emailAncien = nouvelEmail();
        SocieteIdentite perimee = inscrire(emailAncien, siren);
        vieillir(perimee, 73);
        AtomicBoolean premiereRecherche = new AtomicBoolean(true);
        // L'inscription voit la ligne, puis le nettoyage (autre fil, autre transaction) la libere et valide.
        RegistreSocietes registreEnRetard = new RegistreSocietes(identites) {
            @Override
            public Optional<SocieteIdentite> trouver(IdentiteSociete identite) {
                if (premiereRecherche.getAndSet(false)) {
                    assertThat(CompletableFuture.supplyAsync(() -> service.liberer(perimee)).join()).isTrue();
                    return Optional.of(perimee);
                }
                return super.trouver(identite);
            }
        };
        InscriptionSocieteService enRetard = new InscriptionSocieteService(userRepository, customerRepository,
                siteRepository, contactRepository, portalUserService, registreEnRetard, appartenanceRepository,
                demandes, transactionManager, jdbc, 72);

        String email = nouvelEmail();
        ResultatInscription resultat = enRetard.inscrireClient(demande(email, siren));

        assertThat(resultat).isInstanceOf(ResultatInscription.Creee.class);
        SocieteIdentite nouvelle = identites.findBySiren(siren).orElseThrow();
        assertThat(nouvelle.getId()).isNotEqualTo(perimee.getId());
        assertThat(compter("SELECT COUNT(*) FROM users WHERE email = ?", email)).isEqualTo(1);
        assertThat(empreinte(perimee.getId(), perimee.getFicheId(), emailAncien).vide()).isTrue();
    }

    // ── Nettoyage des donnees de test ────────────────────────────────────

    @AfterEach
    void nettoyer() {
        for (Long id : requetes) {
            jdbc.update("DELETE FROM requests WHERE id = ?", id);
        }
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
        if (tenantDuTest != null) {
            jdbc.update("DELETE FROM tenants WHERE id = ?", tenantDuTest);
        }
    }
}
