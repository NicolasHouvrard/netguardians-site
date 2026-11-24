package com.logicielpourtous.site.features.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.role.RoleRepository;
import com.logicielpourtous.site.features.user.Appartenance;
import com.logicielpourtous.site.features.user.AppartenanceRepository;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.PorteeAcces;

/**
 * Entree du partenaire dans l'espace d'un client : droit {@code clients.entrer} sur SA societe, client rattache a CE
 * partenaire, droits effectifs = manager inter plafond, journal ecrit dans sa propre transaction.
 */
@SpringBootTest
@Transactional
class AccesEspaceClientServiceTest {

    private static final String ENTRER = "clients.entrer";
    private static final String LIRE = "clients.lire";
    private static final String ECRIRE = "clients.ecrire";

    @Autowired private AccesEspaceClientService acces;
    @Autowired private DroitsService droits;
    @Autowired private LienClientRepository liens;
    @Autowired private CapaciteSocieteRepository capacites;
    @Autowired private AppartenanceRepository appartenances;
    @Autowired private TenantRepository tenants;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager tm;

    private Long partenaire;
    private Long client;
    private Role chargeDeCompte;
    private Role gestionnaire;
    private Tenant tenant;
    private User userPartenaire;
    private final List<Long> usersCrees = new ArrayList<>();

    @BeforeEach
    void preparer() {
        String n = UUID.randomUUID().toString().substring(0, 8);
        partenaire = societe("PA" + n, "FRPA" + n);
        client = societe("CL" + n, "FRCL" + n);
        chargeDeCompte = role("ROLE_CHARGE_COMPTE_TEST");
        gestionnaire = role("ROLE_GESTIONNAIRE");
        tenant = new Tenant();
        tenant.setName("Tenant acces");
        tenant.setSchemaName("test_tenant_" + UUID.randomUUID());
        tenant = tenants.save(tenant);
        userPartenaire = utilisateur();
        // Le partenaire peut entrer (droit sur SA societe) ; le client est le sien, de capacite CLIENT.
        droits.fixerPlafond(partenaire, Set.of(ENTRER));
        droits.accorderAuRole(partenaire, chargeDeCompte.getName(), Set.of(ENTRER));
        appartenir(userPartenaire, partenaire, chargeDeCompte);
        lier(client, partenaire);
        // Plafond du client : lire + ecrire, tous deux accordes au role manager.
        droits.fixerPlafond(client, Set.of(LIRE, ECRIRE));
        droits.accorderAuRole(client, gestionnaire.getName(), Set.of(LIRE, ECRIRE));
    }

    @AfterEach
    void nettoyer() {
        // Les lignes de journal sont committees a part (REQUIRES_NEW) : le rollback du test ne les retire pas.
        TransactionTemplate tx = new TransactionTemplate(tm);
        tx.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
        tx.executeWithoutResult(s -> {
            for (Long id : usersCrees) {
                jdbc.update("DELETE FROM journal_acces_partenaire WHERE partenaire_user_id = ?", id);
            }
        });
    }

    private Long societe(String siren, String tva) {
        return jdbc.queryForObject("INSERT INTO societes_identites (siren, tva, type) VALUES (?, ?, 'CLIENT') RETURNING id",
                Long.class, siren, tva);
    }

    private Role role(String nom) {
        return roles.findByName(nom).orElseGet(() -> roles.save(new Role(nom, nom, "ESN")));
    }

    private User utilisateur() {
        User u = new User();
        u.setEmail("acces-" + UUID.randomUUID() + "@logicielpourtous.com");
        u.setPassword("test");
        u.setFullName("Test");
        u.setActive(true);
        u.setTenant(tenant);
        u = users.saveAndFlush(u);
        usersCrees.add(u.getId());
        return u;
    }

    private void appartenir(User u, Long societeId, Role... rolesDetenus) {
        Appartenance a = new Appartenance();
        a.setUserId(u.getId());
        a.setTenant(tenant);
        a.setSocieteId(societeId);
        a.setRoles(Set.of(rolesDetenus));
        appartenances.saveAndFlush(a);
    }

    private void lier(Long clientId, Long partenaireId) {
        LienClient l = new LienClient();
        l.setClientSocieteId(clientId);
        l.setPartenaireSocieteId(partenaireId);
        l.setOrigine("PARTENAIRE");
        liens.saveAndFlush(l);
        CapaciteSociete c = new CapaciteSociete();
        c.setSocieteId(clientId);
        c.setCapacite(TypeCapacite.CLIENT);
        capacites.saveAndFlush(c);
    }

    /** Lignes de journal lues dans une transaction a part : seul ce qui a ete committe est visible. */
    private List<Map<String, Object>> journal(Long userId) {
        TransactionTemplate tx = new TransactionTemplate(tm);
        tx.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
        return tx.execute(s -> jdbc.queryForList(
                "SELECT partenaire_user_id, client_societe_id, action, cree_a FROM journal_acces_partenaire "
                        + "WHERE partenaire_user_id = ? ORDER BY id", userId));
    }

    @Test
    void lePartenaireReferentEntreAvecManagerInterPlafondEtUneLigneDeJournal() {
        PorteeAcces portee = acces.entrer(userPartenaire.getId(), partenaire, client);

        assertThat(portee.societeId()).isEqualTo(client);
        assertThat(portee.droitsEffectifs()).containsExactlyInAnyOrder(LIRE, ECRIRE);
        List<Map<String, Object>> lignes = journal(userPartenaire.getId());
        assertThat(lignes).hasSize(1);
        assertThat(lignes.get(0)).containsEntry("client_societe_id", client).containsEntry("action", "ENTREE");
        assertThat(lignes.get(0).get("cree_a")).isNotNull();
    }

    @Test
    void unPartenaireNonReferentEstRefuseEtRienNEstJournaliseCommeSucces() {
        String n = UUID.randomUUID().toString().substring(0, 8);
        Long autreClient = societe("AC" + n, "FRAC" + n);
        Long autrePartenaire = societe("AP" + n, "FRAP" + n);
        lier(autreClient, autrePartenaire);

        assertThatThrownBy(() -> acces.entrer(userPartenaire.getId(), partenaire, autreClient))
                .isInstanceOf(AccesRefuseException.class);

        assertThat(journal(userPartenaire.getId())).extracting(l -> l.get("action")).containsExactly("ENTREE_REFUSEE");
    }

    @Test
    void unClientInconnuEtUnClientDUnAutreDonnentLeMemeRefus() {
        String n = UUID.randomUUID().toString().substring(0, 8);
        Long autreClient = societe("AD" + n, "FRAD" + n);
        Long autrePartenaire = societe("AQ" + n, "FRAQ" + n);
        lier(autreClient, autrePartenaire);

        Throwable inconnu = catchThrowable(() -> acces.entrer(userPartenaire.getId(), partenaire, Long.MAX_VALUE));
        Throwable dAutrui = catchThrowable(() -> acces.entrer(userPartenaire.getId(), partenaire, autreClient));

        assertThat(inconnu).isInstanceOf(AccesRefuseException.class);
        assertThat(dAutrui).isInstanceOf(AccesRefuseException.class);
        assertThat(inconnu.getMessage()).isEqualTo(dAutrui.getMessage());
    }

    @Test
    void sansLeDroitEntrerLAccesEstRefuseEtLaTentativeJournalisee() {
        User autre = utilisateur();
        appartenir(autre, partenaire, role("ROLE_SANS_ENTRER_TEST"));

        assertThatThrownBy(() -> acces.entrer(autre.getId(), partenaire, client)).isInstanceOf(AccesRefuseException.class);

        assertThat(journal(autre.getId())).extracting(l -> l.get("action")).containsExactly("ENTREE_REFUSEE");
    }

    @Test
    void unDroitRetireDuPlafondDisparaitDesDroitsEffectifs() {
        droits.fixerPlafond(client, Set.of(LIRE));

        PorteeAcces portee = acces.entrer(userPartenaire.getId(), partenaire, client);

        assertThat(portee.droitsEffectifs()).containsExactly(LIRE);
    }

    @Test
    void sansPlafondLesDroitsEffectifsSontVides() {
        droits.fixerPlafond(client, Set.of());

        PorteeAcces portee = acces.entrer(userPartenaire.getId(), partenaire, client);

        assertThat(portee.droitsEffectifs()).isEmpty();
    }

    @Test
    void leManagerDuClientNObtientAucuneElevationParCeChemin() {
        User manager = utilisateur();
        appartenir(manager, client, gestionnaire);
        // Pire cas : le droit d'entrer figure meme au plafond et aux reglages de la societe du client.
        droits.fixerPlafond(client, Set.of(LIRE, ECRIRE, ENTRER));
        droits.accorderAuRole(client, gestionnaire.getName(), Set.of(LIRE, ECRIRE, ENTRER));

        // Se faire passer pour le partenaire de sa propre societe : le client n'est pas rattache a lui-meme.
        assertThatThrownBy(() -> acces.entrer(manager.getId(), client, client)).isInstanceOf(AccesRefuseException.class);
        // Reclamer la societe du partenaire : aucune appartenance, donc aucun droit.
        assertThatThrownBy(() -> acces.entrer(manager.getId(), partenaire, client)).isInstanceOf(AccesRefuseException.class);

        assertThat(journal(manager.getId())).extracting(l -> l.get("action")).containsOnly("ENTREE_REFUSEE");
    }

    @Test
    void parametresNulsEchouentFerme() {
        assertThatThrownBy(() -> acces.entrer(null, partenaire, client)).isInstanceOf(AccesRefuseException.class);
        assertThatThrownBy(() -> acces.entrer(userPartenaire.getId(), null, client)).isInstanceOf(AccesRefuseException.class);
        assertThatThrownBy(() -> acces.entrer(userPartenaire.getId(), partenaire, null)).isInstanceOf(AccesRefuseException.class);
        assertThat(journal(userPartenaire.getId())).isEmpty();
    }

    @Test
    void uneErreurDeJournalDUnRefusNeCasseJamaisLeRefus() {
        JournalAccesPartenaire casse = mock(JournalAccesPartenaire.class);
        doThrow(new IllegalStateException("base indisponible")).when(casse).enregistrer(any(), any(), anyString());
        AccesEspaceClientService service = new AccesEspaceClientService(droits, liens, capacites, casse);

        assertThatThrownBy(() -> service.entrer(userPartenaire.getId(), partenaire, Long.MAX_VALUE))
                .isInstanceOf(AccesRefuseException.class);
    }

    @Test
    void uneErreurDeJournalDUnSuccesEmpecheLEntree() {
        JournalAccesPartenaire casse = mock(JournalAccesPartenaire.class);
        doThrow(new IllegalStateException("base indisponible")).when(casse).enregistrer(any(), any(), anyString());
        AccesEspaceClientService service = new AccesEspaceClientService(droits, liens, capacites, casse);

        assertThatThrownBy(() -> service.entrer(userPartenaire.getId(), partenaire, client))
                .isNotInstanceOf(AccesRefuseException.class)
                .isInstanceOf(IllegalStateException.class);
    }

}
