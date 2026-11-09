package com.logicielpourtous.site.features.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicielpourtous.site.features.auth.InscriptionSocieteService.DemandeClient;
import com.logicielpourtous.site.features.auth.InscriptionSocieteService.ResultatInscription;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.customers.ContactRepository;
import com.logicielpourtous.site.features.customers.Customer;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.customers.SiteRepository;
import com.logicielpourtous.site.features.identite.EnregistrementConcurrentException;
import com.logicielpourtous.site.features.identite.IdentiteInvalideException;
import com.logicielpourtous.site.features.identite.IdentiteSociete;
import com.logicielpourtous.site.features.identite.NumerosSociete;
import com.logicielpourtous.site.features.identite.RegistreSocietes;
import com.logicielpourtous.site.features.identite.SocieteIdentite;
import com.logicielpourtous.site.features.identite.SocieteIdentiteRepository;
import com.logicielpourtous.site.features.identite.TypeSociete;
import com.logicielpourtous.site.features.portal.PortalUserService;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.user.Appartenance;
import com.logicielpourtous.site.features.user.AppartenanceRepository;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;

/**
 * Inscription autonome d'un client : identite obligatoire, registre unique, rien d'orphelin.
 *
 * <p>Volontairement SANS {@code @Transactional} : le service ouvre ses propres transactions (et en rejoue une
 * en cas de conflit), il faut donc observer ce qui est reellement valide en base. Chaque test utilise des
 * numeros et des e-mails aleatoires, nettoyes apres coup.
 */
@SpringBootTest
class InscriptionClientTest {

    @Autowired private InscriptionSocieteService service;
    @Autowired private RegistreSocietes registre;
    @Autowired private SocieteIdentiteRepository identites;
    @Autowired private DemandeSocieteConnueRepository demandes;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private SiteRepository siteRepository;
    @Autowired private ContactRepository contactRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AppartenanceRepository appartenanceRepository;
    @Autowired private PortalUserService portalUserService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantRepository tenantRepository;

    /** Tenant cree pour le test quand la base n'en a aucun (le compte portail en exige un) ; supprime apres. */
    private Long tenantDuTest;

    private final List<String> emails = new ArrayList<>();
    private final List<String> sirens = new ArrayList<>();
    private final List<String> tvas = new ArrayList<>();

    @BeforeEach
    void tenantDisponible() {
        if (tenantRepository.count() == 0) {
            Tenant tenant = new Tenant();
            tenant.setName("Tenant inscription test");
            tenant.setSchemaName("test_inscription_" + UUID.randomUUID());
            tenantDuTest = tenantRepository.save(tenant).getId();
        }
    }

    // ── Jeux de donnees ──────────────────────────────────────────────────

    /** SIREN aleatoire valide (cle de Luhn), hors La Poste. */
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

    private static String siretDe(String siren) {
        for (int cle = 0; cle <= 9; cle++) {
            String siret = siren + "0001" + cle;
            if (NumerosSociete.siretValide(siret)) {
                return siret;
            }
        }
        throw new IllegalStateException("aucun SIRET valide pour " + siren);
    }

    private String nouvelEmail() {
        String email = "inscription-" + UUID.randomUUID() + "@example.com";
        emails.add(email);
        return email;
    }

    private DemandeClient demande(String email, String siren, String siret, String tva) {
        return new DemandeClient("Societe Test", "Alice Martin", email, "MotDePasse!1", siren, siret, tva);
    }

    private long nbCustomersAvecSiren(String siren) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM customers WHERE siren = ?", Long.class, siren);
    }

    private long nbIdentitesAvecSiren(String siren) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM societes_identites WHERE siren = ?", Long.class, siren);
    }

    private long nbDemandesPour(Long societeIdentiteId, String email) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM demandes_societe_connue WHERE societe_identite_id = ? AND email_demandeur = ?",
                Long.class, societeIdentiteId, email);
    }

    // ── (a) societe libre ────────────────────────────────────────────────

    @Test
    void societeLibre_creeClientRegistreSiegeContactCompteInactifEtAppartenance() {
        String siren = nouveauSiren();
        String siret = siretDe(siren);
        String tva = NumerosSociete.tvaFrancaise(siren);
        String email = nouvelEmail();

        // Saisie non normalisee : espaces dans le SIRET, TVA en minuscules
        ResultatInscription resultat = service.inscrireClient(
                demande(email, null, siret.substring(0, 9) + " " + siret.substring(9), tva.toLowerCase()));

        assertThat(resultat).isInstanceOf(ResultatInscription.Creee.class);
        ResultatInscription.Creee creee = (ResultatInscription.Creee) resultat;
        assertThat(creee.jetonVerification()).isNotBlank();

        SocieteIdentite identite = identites.findBySiren(siren).orElseThrow();
        assertThat(identite.getType()).isEqualTo(TypeSociete.CLIENT);
        assertThat(identite.getProprietaireTenantId()).isNull();
        assertThat(identite.getTva()).isEqualTo(tva);
        assertThat(identite.getFicheType()).isEqualTo("customers");
        assertThat(identite.getFicheId()).isNotNull();

        Customer customer = customerRepository.findById(identite.getFicheId()).orElseThrow();
        assertThat(customer.getCompanyName()).isEqualTo("Societe Test");
        assertThat(customer.getSiren()).isEqualTo(siren);
        assertThat(customer.getSiret()).isEqualTo(siret);
        assertThat(customer.getVatNumber()).isEqualTo(tva);
        assertThat(customer.getEmail()).isEqualTo(email);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sites WHERE customer_id = ? AND name = 'Siège'", Long.class, customer.getId()))
                .isEqualTo(1L);

        User user = userRepository.findByEmailWithRoles(email).orElseThrow();
        assertThat(user.getId()).isEqualTo(creee.utilisateur().getId());
        assertThat(user.isActive()).isFalse();
        assertThat(user.getEmailVerificationToken()).isEqualTo(creee.jetonVerification());
        assertThat(user.getCustomerId()).isEqualTo(customer.getId());
        assertThat(user.getRoles()).extracting(Role::getName).containsExactly("GESTIONNAIRE");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM contacts WHERE customer_id = ? AND email = ? AND userid = ?",
                Long.class, customer.getId(), email, user.getId()))
                .isEqualTo(1L);

        List<Appartenance> appartenances = appartenanceRepository.findByUserIdOrderById(user.getId());
        assertThat(appartenances).hasSize(1);
        Appartenance a = appartenances.get(0);
        assertThat(a.getCustomerId()).isEqualTo(customer.getId());
        assertThat(a.getTenant().getId()).isEqualTo(user.getTenant().getId());
        assertThat(a.getRoles()).extracting(Role::getName).containsExactly("GESTIONNAIRE");
    }

    // ── (b) societe deja connue, saisie differente ───────────────────────

    @Test
    void memeSocieteParSiretEtTvaMinusculesAvecEspaces_estDejaConnue_sansSecondClient() {
        String siren = nouveauSiren();
        String tva = NumerosSociete.tvaFrancaise(siren);
        assertThat(service.inscrireClient(demande(nouvelEmail(), siren, null, tva)))
                .isInstanceOf(ResultatInscription.Creee.class);

        String email2 = nouvelEmail();
        String tvaSaisie = " " + tva.substring(0, 4).toLowerCase() + " " + tva.substring(4, 7) + " " + tva.substring(7);
        ResultatInscription resultat = service.inscrireClient(demande(email2, null, siretDe(siren), tvaSaisie));

        assertThat(resultat).isInstanceOf(ResultatInscription.DejaConnue.class);
        assertThat(nbCustomersAvecSiren(siren)).isEqualTo(1L);
        assertThat(nbIdentitesAvecSiren(siren)).isEqualTo(1L);
        assertThat(userRepository.findByEmail(email2)).isEmpty();
        Long societeId = identites.findBySiren(siren).orElseThrow().getId();
        assertThat(nbDemandesPour(societeId, email2)).isEqualTo(1L);
        DemandeSocieteConnue demande = demandes.findAll().stream()
                .filter(x -> email2.equals(x.getEmailDemandeur())).findFirst().orElseThrow();
        assertThat(demande.getNomDemandeur()).isEqualTo("Alice Martin");
        assertThat(demande.isTraitee()).isFalse();
    }

    @Test
    void societeSuivieParUnPartenaire_memeIssueNeutre_rienCree() {
        String siren = nouveauSiren();
        String tva = NumerosSociete.tvaFrancaise(siren);
        SocieteIdentite duPartenaire = registre.enregistrer(TypeSociete.CLIENT,
                IdentiteSociete.depuis(siren, null, tva), 7L);

        String email = nouvelEmail();
        ResultatInscription resultat = service.inscrireClient(demande(email, siren, null, tva));

        assertThat(resultat).isInstanceOf(ResultatInscription.DejaConnue.class);
        assertThat(nbCustomersAvecSiren(siren)).isZero();
        assertThat(userRepository.findByEmail(email)).isEmpty();
        assertThat(nbDemandesPour(duPartenaire.getId(), email)).isEqualTo(1L);
        // Le registre n'est pas modifie (proprietaire et fiche inchanges)
        SocieteIdentite relue = identites.findById(duPartenaire.getId()).orElseThrow();
        assertThat(relue.getProprietaireTenantId()).isEqualTo(7L);
        assertThat(relue.getFicheId()).isNull();
    }

    // ── (c) TVA etrangere ────────────────────────────────────────────────

    @Test
    void tvaEtrangere_estRefuseeSurLeChampTva_rienCree() {
        String email = nouvelEmail();
        tvas.add("DE123456789");

        assertThatThrownBy(() -> service.inscrireClient(demande(email, null, null, "DE123456789")))
                .isInstanceOf(IdentiteInvalideException.class)
                .satisfies(e -> assertThat(((IdentiteInvalideException) e).champ()).isEqualTo("tva"));

        assertThat(userRepository.findByEmail(email)).isEmpty();
        assertThat(identites.findByTva("DE123456789")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customers WHERE email = ?", Long.class, email)).isZero();
    }

    @Test
    void tvaIncoherenteAvecLeSiren_estRefuseeSurLeChampTva_rienCree() {
        String siren = nouveauSiren();
        String autre = nouveauSiren();
        String email = nouvelEmail();

        assertThatThrownBy(() -> service.inscrireClient(demande(email, siren, null, NumerosSociete.tvaFrancaise(autre))))
                .isInstanceOf(IdentiteInvalideException.class)
                .satisfies(e -> assertThat(((IdentiteInvalideException) e).champ()).isEqualTo("tva"));

        assertThat(nbIdentitesAvecSiren(siren)).isZero();
        assertThat(userRepository.findByEmail(email)).isEmpty();
    }

    // ── (d) e-mail deja utilise ──────────────────────────────────────────

    @Test
    void emailDejaUtilise_estRefuseAvantToutEcriture() {
        String email = nouvelEmail();
        String siren1 = nouveauSiren();
        service.inscrireClient(demande(email, siren1, null, NumerosSociete.tvaFrancaise(siren1)));

        String siren2 = nouveauSiren();
        assertThatThrownBy(() -> service.inscrireClient(demande(email, siren2, null, NumerosSociete.tvaFrancaise(siren2))))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cet email est déjà utilisé.");

        assertThat(nbIdentitesAvecSiren(siren2)).isZero();
        assertThat(nbCustomersAvecSiren(siren2)).isZero();
    }

    // ── (e) echec apres l'enregistrement au registre ─────────────────────

    @Test
    void echecApresLeRegistre_rienNeSubsiste() {
        PortalUserService comptesEnPanne = new PortalUserService(null, null, null, null) {
            @Override
            public CreationResult createExplicit(String email, String fullName, String rawPassword,
                                                 Long customerId, String roleName) {
                throw new IllegalStateException("panne simulee a la creation du compte");
            }
        };
        InscriptionSocieteService enPanne = new InscriptionSocieteService(userRepository, customerRepository,
                siteRepository, contactRepository, comptesEnPanne, registre, appartenanceRepository, demandes,
                transactionManager);
        String siren = nouveauSiren();
        String email = nouvelEmail();

        assertThatThrownBy(() -> enPanne.inscrireClient(demande(email, siren, null, NumerosSociete.tvaFrancaise(siren))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("panne simulee");

        assertThat(nbIdentitesAvecSiren(siren)).isZero();
        assertThat(nbCustomersAvecSiren(siren)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contacts WHERE email = ?", Long.class, email)).isZero();
        assertThat(userRepository.findByEmail(email)).isEmpty();
    }

    // ── Conflit concurrent : la base refuse, on rejoue une fois ─────────

    /** Registre dont le 1er enregistrement simule une inscription concurrente gagnante puis un refus de la base. */
    private RegistreSocietes registreAvecConcurrent(AtomicInteger appels, boolean toujoursEnConflit) {
        TransactionTemplate autreTransaction = new TransactionTemplate(transactionManager);
        autreTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new RegistreSocietes(identites) {
            @Override
            public SocieteIdentite enregistrer(TypeSociete type, IdentiteSociete identite, Long proprietaire) {
                int n = appels.incrementAndGet();
                if (n == 1) {
                    // L'autre inscription valide la societe pendant la notre
                    autreTransaction.executeWithoutResult(s -> registre.enregistrer(type, identite, proprietaire));
                }
                if (n == 1 || toujoursEnConflit) {
                    throw new EnregistrementConcurrentException(new RuntimeException("23505 simule"));
                }
                return super.enregistrer(type, identite, proprietaire);
            }
        };
    }

    @Test
    void conflitConcurrent_estRejoueUneFois_puisDejaConnue() {
        AtomicInteger appels = new AtomicInteger();
        InscriptionSocieteService avecConcurrent = new InscriptionSocieteService(userRepository, customerRepository,
                siteRepository, contactRepository, portalUserService, registreAvecConcurrent(appels, false),
                appartenanceRepository, demandes, transactionManager);
        String siren = nouveauSiren();
        String email = nouvelEmail();

        ResultatInscription resultat = avecConcurrent.inscrireClient(
                demande(email, siren, null, NumerosSociete.tvaFrancaise(siren)));

        assertThat(resultat).isInstanceOf(ResultatInscription.DejaConnue.class);
        assertThat(appels.get()).isEqualTo(2);
        assertThat(nbIdentitesAvecSiren(siren)).isEqualTo(1L);
        assertThat(nbCustomersAvecSiren(siren)).isZero();
        assertThat(userRepository.findByEmail(email)).isEmpty();
        Long societeId = identites.findBySiren(siren).orElseThrow().getId();
        assertThat(nbDemandesPour(societeId, email)).isEqualTo(1L);
    }

    @Test
    void conflitConcurrentRepete_neRejoueQuUneFois_etResteNeutre() {
        AtomicInteger appels = new AtomicInteger();
        InscriptionSocieteService avecConcurrent = new InscriptionSocieteService(userRepository, customerRepository,
                siteRepository, contactRepository, portalUserService, registreAvecConcurrent(appels, true),
                appartenanceRepository, demandes, transactionManager);
        String siren = nouveauSiren();
        String email = nouvelEmail();

        ResultatInscription resultat = avecConcurrent.inscrireClient(
                demande(email, siren, null, NumerosSociete.tvaFrancaise(siren)));

        assertThat(resultat).isInstanceOf(ResultatInscription.DejaConnue.class);
        assertThat(appels.get()).isEqualTo(2);
        assertThat(nbCustomersAvecSiren(siren)).isZero();
        assertThat(userRepository.findByEmail(email)).isEmpty();
    }

    // ── Nettoyage ────────────────────────────────────────────────────────

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
        if (tenantDuTest != null) {
            jdbc.update("DELETE FROM tenants WHERE id = ?", tenantDuTest);
        }
    }
}
