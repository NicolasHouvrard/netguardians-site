package com.logicielpourtous.site.features.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.logicielpourtous.site.features.identite.NumerosSociete;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.CreationClient;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.ResultatCreation;
import com.logicielpourtous.site.sdk.GestionPartenairesContrat;
import com.logicielpourtous.site.sdk.GestionPartenairesContrat.CreationPartenaire;
import com.logicielpourtous.site.sdk.GestionPartenairesContrat.PartenaireResume;
import com.logicielpourtous.site.sdk.GestionPartenairesContrat.ResultatPartenaire;
import com.logicielpourtous.site.sdk.IdentiteContrat;
import com.logicielpourtous.site.sdk.IdentiteContrat.IdentiteCourante;

/** Contrat de gestion des partenaires : reserve a l'admin du serveur maitre, garde lue dans le bean d'identite. */
@SpringBootTest
class GestionPartenairesContratTest {

    private static final long ADMIN = 987_654_322L;

    @Autowired private GestionPartenairesContrat contrat;
    @Autowired private CreationPartenaireService creation;
    @Autowired private ModeleDroitsPartenaireService modele;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private IdentiteContrat identite;

    private final List<String> sirensPartenaires = new ArrayList<>();
    private final List<String> sirensClients = new ArrayList<>();
    private final List<String> emails = new ArrayList<>();

    private Set<String> modeleInitial;
    private long schemasAvant;
    private long tenantsAvant;

    @BeforeEach
    void memoriser() {
        modeleInitial = modele.droits();
        schemasAvant = schemas();
        tenantsAvant = compter("tenants");
    }

    @AfterEach
    void nettoyer() {
        modele.remplacer(modeleInitial);
        jdbc.update("DELETE FROM journal_actions_maitre WHERE admin_user_id = ?", ADMIN);
        // Tenants des partenaires crees : retrouves par leurs utilisateurs (jamais modifies par les tests), car les tests
        // de refus alterent proprietaire_tenant_id sur la societe.
        Set<Long> tenantsCrees = new java.util.LinkedHashSet<>();
        for (String email : emails) {
            tenantsCrees.addAll(jdbc.queryForList("SELECT tenant_id FROM users WHERE email = ?", Long.class, email));
            jdbc.update("DELETE FROM appartenances_roles WHERE appartenance_id IN "
                    + "(SELECT a.id FROM appartenances a JOIN users u ON u.id = a.user_id WHERE u.email = ?)", email);
            jdbc.update("DELETE FROM appartenances WHERE user_id IN (SELECT id FROM users WHERE email = ?)", email);
            jdbc.update("DELETE FROM users_roles WHERE user_id IN (SELECT id FROM users WHERE email = ?)", email);
            jdbc.update("DELETE FROM users WHERE email = ?", email);
        }
        for (String siren : sirensClients) {
            supprimerSociete(siren);
        }
        for (String siren : sirensPartenaires) {
            supprimerSociete(siren);
        }
        for (Long tenant : tenantsCrees) {
            String schema = jdbc.queryForObject("SELECT schema_name FROM tenants WHERE id = ?", String.class, tenant);
            jdbc.update("DELETE FROM tenants WHERE id = ?", tenant);
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
        assertThat(schemas()).as("schemas p_* laisses par le test").isEqualTo(schemasAvant);
        assertThat(compter("tenants")).as("tenants laisses par le test").isEqualTo(tenantsAvant);
    }

    private void supprimerSociete(String siren) {
        for (Long id : jdbc.queryForList("SELECT id FROM societes_identites WHERE siren = ?", Long.class, siren)) {
            jdbc.update("DELETE FROM liens_clients WHERE client_societe_id = ? OR partenaire_societe_id = ?", id, id);
            jdbc.update("DELETE FROM reglages_manager WHERE client_societe_id = ?", id);
            jdbc.update("DELETE FROM plafonds_client WHERE client_societe_id = ?", id);
            jdbc.update("DELETE FROM partenaires_etat WHERE societe_id = ?", id);
            jdbc.update("DELETE FROM societe_capacites WHERE societe_id = ?", id);
            jdbc.update("DELETE FROM appartenances WHERE societe_id = ?", id);
            jdbc.update("DELETE FROM societes_identites WHERE id = ?", id);
        }
    }

    private String siren(List<String> liste) {
        for (int n = 700000000 + (int) (Math.random() * 90000000);; n++) {
            String s = String.valueOf(n);
            if (NumerosSociete.sirenValide(s)
                    && jdbc.queryForObject("SELECT count(*) FROM societes_identites WHERE siren = ?", Long.class, s) == 0) {
                liste.add(s);
                return s;
            }
        }
    }

    private String email() {
        String e = "gestion-" + UUID.randomUUID() + "@example.test";
        emails.add(e);
        return e;
    }

    private void identite(String role, String roleServeur, Long userId) {
        when(identite.courante()).thenReturn(new IdentiteCourante(userId, Set.of(role), null, null, roleServeur));
    }

    private void adminDuMaitre() {
        identite("ROLE_ADMIN", "MASTER", ADMIN);
    }

    private long schemas() {
        return jdbc.queryForObject("SELECT count(*) FROM information_schema.schemata WHERE schema_name LIKE 'p\\_%'",
                Long.class);
    }

    private long compter(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private List<Long> compteurs() {
        return List.of(compter("societes_identites"), compter("users"), compter("tenants"),
                compter("journal_actions_maitre"), compter("liens_clients"), compter("modele_droits_partenaire"));
    }

    private CreationPartenaire demandePartenaire() {
        return new CreationPartenaire(siren(sirensPartenaires), null, "Maison Gestion SARL", email(), "Alice Martin");
    }

    private Long partenaire() {
        // Cree directement par le service (sans passer par le contrat) pour ne pas melanger les journaux.
        ResultatPartenaire r = creation.creer(demandePartenaire());
        assertThat(r.issue()).isEqualTo("CREE");
        return r.societeId();
    }

    private CreationClient demandeClient(Long partenaire) {
        return new CreationClient(partenaire, false, siren(sirensClients), null, false, email(), "Bob Client");
    }

    // --- Refus : les cinq methodes, tous les profils qui ne sont pas l'admin du maitre ---

    private Map<String, Runnable> methodes(Long partenaire) {
        Map<String, Runnable> m = new LinkedHashMap<>();
        CreationPartenaire dp = demandePartenaire();
        CreationClient dc = demandeClient(partenaire);
        m.put("creerPartenaire", () -> contrat.creerPartenaire(dp));
        m.put("creerClientPourPartenaire", () -> contrat.creerClientPourPartenaire(dc));
        m.put("listerPartenaires", () -> contrat.listerPartenaires());
        m.put("modeleDroits", () -> contrat.modeleDroits());
        m.put("remplacerModeleDroits", () -> contrat.remplacerModeleDroits(Set.of("x.y")));
        m.put("listerClientsDuPartenaire", () -> contrat.listerClientsDuPartenaire(partenaire));
        return m;
    }

    private void verifierRefus(Runnable preparation) {
        Long p = partenaire();
        Map<String, Runnable> methodes = methodes(p);
        Set<String> avantModele = modele.droits();
        List<Long> avant = compteurs();
        preparation.run();
        methodes.forEach((nom, appel) -> assertThatThrownBy(appel::run).as(nom)
                .isInstanceOf(SecurityException.class)
                .hasMessage("Réservé à l'administrateur du serveur maître."));
        assertThat(compteurs()).isEqualTo(avant);
        assertThat(modele.droits()).isEqualTo(avantModele);
    }

    @Test
    void gestionnaireDuMaitreRefuse() {
        verifierRefus(() -> identite("ROLE_GESTIONNAIRE", "MASTER", ADMIN));
    }

    @Test
    void adminSurServeurPartenaireRefuse() {
        verifierRefus(() -> identite("ROLE_ADMIN", "PARTNER", ADMIN));
    }

    @Test
    void adminSansRoleServeurRefuse() {
        verifierRefus(() -> identite("ROLE_ADMIN", null, ADMIN));
    }

    @Test
    void adminMaitreSansUserIdRefuse() {
        verifierRefus(() -> identite("ROLE_ADMIN", "MASTER", null));
    }

    @Test
    void identiteIndisponibleRefuse() {
        verifierRefus(() -> when(identite.courante()).thenThrow(new IllegalStateException("pas de session")));
    }

    @Test
    void identiteNulleRefuse() {
        verifierRefus(() -> when(identite.courante()).thenReturn(null));
    }

    @Test
    void rolesNulsRefuse() {
        verifierRefus(() -> when(identite.courante())
                .thenReturn(new IdentiteCourante(ADMIN, null, null, null, "MASTER")));
    }

    // --- Admin du maitre ---

    @Test
    void creerPartenaireCreeEtJournalise() {
        adminDuMaitre();
        ResultatPartenaire r = contrat.creerPartenaire(demandePartenaire());

        assertThat(r.issue()).isEqualTo("CREE");
        assertThat(r.societeId()).isNotNull();
        List<Map<String, Object>> lignes = jdbc.queryForList("SELECT action, partenaire_societe_id, objet_societe_id "
                + "FROM journal_actions_maitre WHERE admin_user_id = ?", ADMIN);
        assertThat(lignes).hasSize(1);
        assertThat(lignes.get(0)).containsEntry("action", "PARTENAIRE_CREE")
                .containsEntry("partenaire_societe_id", r.societeId())
                .containsEntry("objet_societe_id", r.societeId());
    }

    @Test
    void creerPartenaireSansCreationNeJournalePas() {
        adminDuMaitre();
        CreationPartenaire d = demandePartenaire();
        assertThat(contrat.creerPartenaire(d).issue()).isEqualTo("CREE");
        long journal = compter("journal_actions_maitre");

        ResultatPartenaire doublon = contrat
            .creerPartenaire(new CreationPartenaire(d.siren(), null, "Autre", email(), "Z Z"));

        assertThat(doublon.issue()).isEqualTo("SIREN_DEJA_CONNU");
        assertThat(doublon.societeId()).isNull();
        assertThat(compter("journal_actions_maitre")).isEqualTo(journal);
    }

    @Test
    void clientCreePourLePartenaireCibleAvecSonTenant() {
        Long cible = partenaire();
        adminDuMaitre();
        CreationClient d = demandeClient(cible);

        ResultatCreation r = contrat.creerClientPourPartenaire(d);

        assertThat(r.issue()).isEqualTo("CREE");
        Long client = r.societeId();
        assertThat(client).isNotNull();
        assertThat(jdbc.queryForObject("SELECT partenaire_societe_id FROM liens_clients WHERE client_societe_id = ?",
                Long.class, client)).isEqualTo(cible);
        assertThat(jdbc.queryForObject("SELECT origine FROM liens_clients WHERE client_societe_id = ?", String.class,
                client)).isEqualTo("PARTENAIRE");
        Long tenantCible = jdbc.queryForObject("SELECT proprietaire_tenant_id FROM societes_identites WHERE id = ?",
                Long.class, cible);
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM users WHERE email = ?", Long.class, d.email()))
                .isEqualTo(tenantCible);
        List<Map<String, Object>> lignes = jdbc.queryForList("SELECT action, partenaire_societe_id, objet_societe_id "
                + "FROM journal_actions_maitre WHERE admin_user_id = ?", ADMIN);
        assertThat(lignes).hasSize(1);
        assertThat(lignes.get(0)).containsEntry("action", "CLIENT_CREE_POUR_PARTENAIRE")
                .containsEntry("partenaire_societe_id", cible)
                .containsEntry("objet_societe_id", client);
    }

    @Test
    void clientSansCreationNeJournalePas() {
        Long cible = partenaire();
        adminDuMaitre();
        CreationClient d = demandeClient(cible);
        assertThat(contrat.creerClientPourPartenaire(d).issue()).isEqualTo("CREE");
        long journal = compter("journal_actions_maitre");

        ResultatCreation r = contrat.creerClientPourPartenaire(
                new CreationClient(cible, false, siren(sirensClients), null, false, d.email(), "Bob"));

        assertThat(r.issue()).isEqualTo("EMAIL_DEJA_UTILISE");
        assertThat(compter("journal_actions_maitre")).isEqualTo(journal);
    }

    private void refuseSansEcriture(CreationClient d) {
        List<Long> avant = compteurs();
        assertThatThrownBy(() -> contrat.creerClientPourPartenaire(d)).isInstanceOf(IllegalArgumentException.class);
        assertThat(compteurs()).isEqualTo(avant);
    }

    @Test
    void cibleInconnueRefusee() {
        adminDuMaitre();
        refuseSansEcriture(new CreationClient(999_999_999L, false, siren(sirensClients), null, false, email(), "Bob"));
    }

    @Test
    void cibleNulleRefusee() {
        adminDuMaitre();
        refuseSansEcriture(new CreationClient(null, false, siren(sirensClients), null, false, email(), "Bob"));
    }

    @Test
    void cibleSansCapacitePartenaireRefusee() {
        Long cible = partenaire();
        jdbc.update("DELETE FROM societe_capacites WHERE societe_id = ?", cible);
        adminDuMaitre();
        refuseSansEcriture(demandeClient(cible));
    }

    @Test
    void cibleSansTenantProprietaireRefusee() {
        Long cible = partenaire();
        jdbc.update("UPDATE societes_identites SET proprietaire_tenant_id = NULL WHERE id = ?", cible);
        adminDuMaitre();
        refuseSansEcriture(demandeClient(cible));
    }

    @Test
    void cibleAvecTenantInexistantRefusee() {
        Long cible = partenaire();
        jdbc.update("UPDATE societes_identites SET proprietaire_tenant_id = 987654321 WHERE id = ?", cible);
        adminDuMaitre();
        refuseSansEcriture(demandeClient(cible));
    }

    @Test
    void listerContientLePartenaireCree() {
        Long id = partenaire();
        adminDuMaitre();

        List<PartenaireResume> liste = contrat.listerPartenaires();

        PartenaireResume p = liste.stream().filter(x -> id.equals(x.societeId())).findFirst().orElseThrow();
        assertThat(p.etat()).isEqualTo("ACTIF");
        assertThat(p.raisonSociale()).isEqualTo("Maison Gestion SARL");
        assertThat(p.siren()).isEqualTo(
                jdbc.queryForObject("SELECT siren FROM societes_identites WHERE id = ?", String.class, id));
    }

    @Test
    void listerNeContientPasLesClients() {
        Long cible = partenaire();
        adminDuMaitre();
        Long client = contrat.creerClientPourPartenaire(demandeClient(cible)).societeId();

        assertThat(contrat.listerPartenaires()).noneMatch(x -> client.equals(x.societeId()));
    }

    @Test
    void etatAbsentVautActif() {
        Long id = partenaire();
        jdbc.update("DELETE FROM partenaires_etat WHERE societe_id = ?", id);
        adminDuMaitre();

        assertThat(contrat.listerPartenaires().stream().filter(x -> id.equals(x.societeId())).findFirst().orElseThrow()
            .etat()).isEqualTo("ACTIF");
    }

    @Test
    void remplacerLeModeleJournalise() {
        adminDuMaitre();

        contrat.remplacerModeleDroits(Set.of("clients.lire", "clients.creer"));

        assertThat(contrat.modeleDroits()).containsExactlyInAnyOrder("clients.lire", "clients.creer");
        assertThat(jdbc.queryForList("SELECT action FROM journal_actions_maitre WHERE admin_user_id = ?", String.class,
                ADMIN)).containsExactly("MODELE_DROITS_MODIFIE");
    }

    @Test
    void droitInvalideNeJournalisePas() {
        adminDuMaitre();

        assertThatThrownBy(() -> contrat.remplacerModeleDroits(Set.of("DROIT INVALIDE!")))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(contrat.modeleDroits()).isEqualTo(modeleInitial);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_actions_maitre WHERE admin_user_id = ?",
                Long.class, ADMIN)).isZero();
    }

    // --- Clients d'un partenaire ---

    @Test
    void clientsDuPartenaireInconnuOuSansCapaciteRefuses() {
        Long p = partenaire();
        adminDuMaitre();
        Long client = contrat.creerClientPourPartenaire(demandeClient(p)).societeId();
        assertThatThrownBy(() -> contrat.listerClientsDuPartenaire(999_999_999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Société partenaire inconnue ou sans capacité partenaire.");
        assertThatThrownBy(() -> contrat.listerClientsDuPartenaire(client))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Société partenaire inconnue ou sans capacité partenaire.");
    }

    @Test
    void clientsDuPartenaireSansClientListeVide() {
        Long p = partenaire();
        adminDuMaitre();
        assertThat(contrat.listerClientsDuPartenaire(p)).isEmpty();
    }

    @Test
    void clientsDuPartenaireTriesParDateDecroissanteSansceuxDesAutres() {
        Long p = partenaire();
        Long autre = partenaire();
        adminDuMaitre();
        CreationClient d1 = demandeClient(p);
        CreationClient d2 = demandeClient(p);
        Long c1 = contrat.creerClientPourPartenaire(d1).societeId();
        Long c2 = contrat.creerClientPourPartenaire(d2).societeId();
        Long cAutre = contrat.creerClientPourPartenaire(demandeClient(autre)).societeId();
        jdbc.update("UPDATE liens_clients SET depuis = '2024-01-01T00:00:00Z' WHERE client_societe_id = ?", c1);
        jdbc.update("UPDATE liens_clients SET depuis = '2025-06-01T00:00:00Z' WHERE client_societe_id = ?", c2);

        List<GestionPartenairesContrat.ClientResume> liste = contrat.listerClientsDuPartenaire(p);

        assertThat(liste).extracting(GestionPartenairesContrat.ClientResume::societeId).containsExactly(c2, c1);
        assertThat(liste).extracting(GestionPartenairesContrat.ClientResume::societeId).doesNotContain(cAutre);
        assertThat(liste.get(0).siren()).isEqualTo(d2.siren());
        assertThat(liste.get(0).origine()).isEqualTo("PARTENAIRE");
        assertThat(liste.get(0).raisonSociale()).isEqualTo("Bob Client");
        assertThat(liste.get(0).depuis()).isEqualTo(java.time.Instant.parse("2025-06-01T00:00:00Z"));
    }

}
