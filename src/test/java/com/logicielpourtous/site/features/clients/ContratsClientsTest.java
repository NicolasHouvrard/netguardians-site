package com.logicielpourtous.site.features.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.role.RoleRepository;
import com.logicielpourtous.site.features.user.Appartenance;
import com.logicielpourtous.site.features.user.AppartenanceRepository;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.ClientResume;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.CreationClient;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.ResultatCreation;
import com.logicielpourtous.site.features.auth.DemandeSocieteConnue;
import com.logicielpourtous.site.features.auth.DemandeSocieteConnueRepository;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.FicheClient;
import com.logicielpourtous.site.sdk.IdentiteContrat;
import com.logicielpourtous.site.sdk.IdentiteContrat.IdentiteCourante;

/** Contrats d'identite et d'annuaire clients offerts aux plugins (implementes par le noyau). */
@SpringBootTest
@Transactional
class ContratsClientsTest {

    @Autowired private IdentiteContrat identite;
    @Autowired private AnnuaireClientsContrat annuaire;
    @Autowired private DroitsService droits;
    @Autowired private LienClientRepository liens;
    @Autowired private CapaciteSocieteRepository capacites;
    @Autowired private AppartenanceRepository appartenances;
    @Autowired private TenantRepository tenants;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private org.springframework.transaction.PlatformTransactionManager tm;
    @Autowired private DemandeSocieteConnueRepository demandes;

    private Tenant tenant;
    private User user;
    private Role role;

    @BeforeEach
    void preparer() {
        role = roles.findByName("ROLE_GESTIONNAIRE").orElseGet(() -> roles.save(new Role("ROLE_GESTIONNAIRE", "G", "ESN")));
        tenant = new Tenant();
        tenant.setName("Tenant contrats");
        tenant.setSchemaName("test_tenant_" + UUID.randomUUID());
        tenant = tenants.save(tenant);
        user = new User();
        user.setEmail("contrats-" + UUID.randomUUID() + "@logicielpourtous.com");
        user.setPassword("test");
        user.setFullName("Test");
        user.setActive(true);
        user.setTenant(tenant);
        user.setRoles(Set.of(role));
        user = users.saveAndFlush(user);
    }

    @AfterEach
    void nettoyer() {
        SecurityContextHolder.clearContext();
        // Le journal d'acces est ecrit en REQUIRES_NEW : le rollback du test ne le retire pas.
        org.springframework.transaction.support.TransactionTemplate tx =
                new org.springframework.transaction.support.TransactionTemplate(tm);
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(st -> jdbc.update("DELETE FROM journal_acces_partenaire WHERE partenaire_user_id = ?",
                user.getId()));
    }

    private Long societe(String siren, String tva) {
        return jdbc.queryForObject("INSERT INTO societes_identites (siren, tva, type) VALUES (?, ?, 'CLIENT') RETURNING id",
                Long.class, siren, tva);
    }

    private Appartenance appartenance(Long societeId) {
        Appartenance a = new Appartenance();
        a.setUserId(user.getId());
        a.setTenant(tenant);
        a.setSocieteId(societeId);
        a.setRoles(Set.of(role));
        return appartenances.saveAndFlush(a);
    }

    private void connecter(CustomUserDetails compte) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(compte, null, compte.getAuthorities()));
    }

    private void lier(Long client, Long partenaire, TypeCapacite capacite) {
        LienClient l = new LienClient();
        l.setClientSocieteId(client);
        l.setPartenaireSocieteId(partenaire);
        l.setOrigine("PARTENAIRE");
        liens.saveAndFlush(l);
        if (capacite != null) {
            CapaciteSociete c = new CapaciteSociete();
            c.setSocieteId(client);
            c.setCapacite(capacite);
            capacites.saveAndFlush(c);
        }
    }

    // ---- Identite ----

    @Test
    void courantRefleteLUtilisateurAuthentifieEtLaSocieteChoisie() {
        Long societe = societe("CT1", "FRCT1");
        Appartenance a = appartenance(societe);
        connecter(new CustomUserDetails(user, List.of(a), a.getId()));

        IdentiteCourante id = identite.courante();

        assertThat(id.userId()).isEqualTo(user.getId());
        assertThat(id.roles()).containsExactly("ROLE_GESTIONNAIRE");
        assertThat(id.societeId()).isEqualTo(societe);
        assertThat(id.tenantId()).isEqualTo(tenant.getId());
        assertThat(id.roleServeur()).isEqualTo("MASTER");
    }

    @Test
    void avecPlusieursAppartenancesLaSocieteEstCelleDeLAppartenanceChoisie() {
        Long s1 = societe("CT2", "FRCT2");
        Long s2 = societe("CT3", "FRCT3");
        Appartenance a1 = appartenance(s1);
        Appartenance a2 = appartenance(s2);
        connecter(new CustomUserDetails(user, List.of(a1, a2), a2.getId()));

        assertThat(identite.courante().societeId()).isEqualTo(s2);
    }

    @Test
    void appartenanceHeritageSansSocieteDonneSocieteNulle() {
        Appartenance a = appartenance(null);
        connecter(new CustomUserDetails(user, List.of(a), a.getId()));

        IdentiteCourante id = identite.courante();

        assertThat(id.societeId()).isNull();
        assertThat(id.userId()).isEqualTo(user.getId());
    }

    @Test
    void sansAppartenanceLaSocieteEstNulle() {
        connecter(new CustomUserDetails(user));

        assertThat(identite.courante().societeId()).isNull();
    }

    @Test
    void choixDeSocieteEnAttenteRefuseLIdentite() {
        Appartenance a1 = appartenance(societe("CT4", "FRCT4"));
        Appartenance a2 = appartenance(societe("CT5", "FRCT5"));
        connecter(new CustomUserDetails(user, List.of(a1, a2), null));

        assertThatThrownBy(() -> identite.courante()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("société");
    }

    @Test
    void horsAuthentificationLeveUneExceptionExplicite() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> identite.courante()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authentifié");
    }

    @Test
    void anonymeEstRefuse() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("k", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        assertThatThrownBy(() -> identite.courante()).isInstanceOf(IllegalStateException.class);
    }

    // ---- Annuaire ----

    @Test
    void clientsDuPartenaireNeRenvoieQueSesClients() {
        Long p1 = societe("CT6", "FRCT6");
        Long p2 = societe("CT7", "FRCT7");
        Long c1 = societe("CT8", "FRCT8");
        Long c2 = societe("CT9", "FRCT9");
        Long c3 = societe("CT10", "FRCT10");
        lier(c1, p1, TypeCapacite.CLIENT);
        lier(c2, p1, TypeCapacite.CLIENT);
        lier(c3, p2, TypeCapacite.CLIENT);

        List<ClientResume> resultat = annuaire.clientsDuPartenaire(p1);

        assertThat(resultat).extracting(ClientResume::societeId).containsExactlyInAnyOrder(c1, c2);
        assertThat(resultat).extracting(ClientResume::origine).containsOnly("PARTENAIRE");
        assertThat(resultat).extracting(ClientResume::siren).containsExactlyInAnyOrder("CT8", "CT9");
        assertThat(annuaire.clientsDuPartenaire(societe("CT11", "FRCT11"))).isEmpty();
    }

    @Test
    void ficheDUneSocieteSansLienClientEstVide() {
        assertThat(annuaire.fiche(societe("CT12", "FRCT12"))).isEmpty();
        assertThat(annuaire.fiche(-1L)).isEmpty();
    }

    @Test
    void ficheDUneSocieteLieeSansCapaciteClientEstVide() {
        Long p = societe("CT13", "FRCT13");
        Long c = societe("CT14", "FRCT14");
        lier(c, p, TypeCapacite.FOURNISSEUR);

        assertThat(annuaire.fiche(c)).isEmpty();
        assertThat(annuaire.clientsDuPartenaire(p)).isEmpty();
    }

    @Test
    void ficheDUnClientLieDonnePartenaireEtCapacites() {
        Long p = societe("CT15", "FRCT15");
        Long c = societe("CT16", "FRCT16");
        lier(c, p, TypeCapacite.CLIENT);
        CapaciteSociete autre = new CapaciteSociete();
        autre.setSocieteId(c);
        autre.setCapacite(TypeCapacite.PARTENAIRE);
        capacites.saveAndFlush(autre);

        Optional<FicheClient> fiche = annuaire.fiche(c);

        assertThat(fiche).isPresent();
        assertThat(fiche.get().partenaireSocieteId()).isEqualTo(p);
        assertThat(fiche.get().capacites()).containsExactly("CLIENT", "PARTENAIRE");
        assertThat(fiche.get().resume().societeId()).isEqualTo(c);
        assertThat(fiche.get().resume().tva()).isEqualTo("FRCT16");
    }

    @Test
    void entreesNullesSontRefusees() {
        assertThatThrownBy(() -> annuaire.fiche(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> annuaire.clientsDuPartenaire(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void autoriseDelegueAuServiceDeDroitsSansRaccourci() {
        DroitsService faux = mock(DroitsService.class);
        when(faux.autorise(1L, 2L, "clients.lire")).thenReturn(true);
        AnnuaireClientsContrat contrat = new AnnuaireClientsContratImpl(liens, capacites, null, faux, null, null, null, null, null, null, null, null, null, null, null);

        assertThat(contrat.autorise(1L, 2L, "clients.lire")).isTrue();
        assertThat(contrat.autorise(1L, 2L, "clients.ecrire")).isFalse();
        verify(faux).autorise(1L, 2L, "clients.lire");
        verify(faux).autorise(1L, 2L, "clients.ecrire");
    }

    @Test
    void autoriseSuitLesTroisCouchesDeDroits() {
        Long c = societe("CT17", "FRCT17");
        Long autre = societe("CT18", "FRCT18");
        appartenance(c);
        droits.fixerPlafond(c, Set.of("clients.lire"));
        droits.accorderAuRole(c, "ROLE_GESTIONNAIRE", Set.of("clients.lire"));

        assertThat(annuaire.autorise(user.getId(), c, "clients.lire")).isTrue();
        assertThat(annuaire.autorise(user.getId(), c, "clients.ecrire")).isFalse();
        assertThat(annuaire.autorise(user.getId(), autre, "clients.lire")).isFalse();
    }

    // ---- Creation d'un client par le partenaire ----

    private static String sirenValide(int graine) {
        String base = String.format("%08d", graine);
        for (int c = 0; c < 10; c++) {
            String candidat = base + c;
            if (com.logicielpourtous.site.features.identite.NumerosSociete.sirenValide(candidat)) {
                return candidat;
            }
        }
        throw new IllegalStateException();
    }

    private Long partenaire() {
        Long p = societe("PA" + UUID.randomUUID().toString().substring(0, 6), "FRPA" + UUID.randomUUID().toString().substring(0, 6));
        CapaciteSociete c = new CapaciteSociete();
        c.setSocieteId(p);
        c.setCapacite(TypeCapacite.PARTENAIRE);
        capacites.saveAndFlush(c);
        return p;
    }

    private String emailNeuf() {
        return "client-" + UUID.randomUUID() + "@example.test";
    }

    private boolean estManagerDe(Long userId, Long societeId) {
        return appartenances.findByUserIdAndSocieteId(userId, societeId).stream()
                .anyMatch(a -> a.getRoles().stream().anyMatch(r -> "ROLE_GESTIONNAIRE".equals(r.getName())));
    }

    @Test
    void creerUnClientEcritSocieteCapaciteLienEtPremierManager() {
        Long p = partenaire();
        String siren = sirenValide(48151623);
        String email = emailNeuf();

        ResultatCreation r = annuaire.creerClient(new CreationClient(p, false, siren, null, false, email, "Alice Martin"));

        assertThat(r.issue()).isEqualTo("CREE");
        Long id = r.societeId();
        assertThat(id).isNotNull();
        assertThat(jdbc.queryForObject("SELECT siren FROM societes_identites WHERE id = ?", String.class, id)).isEqualTo(siren);
        assertThat(capacites.existsBySocieteIdAndCapacite(id, TypeCapacite.CLIENT)).isTrue();
        LienClient lien = liens.findByClientSocieteId(id).orElseThrow();
        assertThat(lien.getPartenaireSocieteId()).isEqualTo(p);
        assertThat(lien.getOrigine()).isEqualTo("PARTENAIRE");
        User cree = users.findByEmail(email).orElseThrow();
        assertThat(estManagerDe(cree.getId(), id)).isTrue();
    }

    @Test
    void creerUnParticulierCreeUneSocieteParticulierLieeAuPartenaire() {
        Long p = partenaire();
        String email = emailNeuf();

        ResultatCreation r = annuaire.creerClient(new CreationClient(p, true, null, null, false, email, "Jean Dupont"));

        assertThat(r.issue()).isEqualTo("CREE");
        Long id = r.societeId();
        assertThat(jdbc.queryForObject("SELECT particulier FROM societes_identites WHERE id = ?", Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT siren FROM societes_identites WHERE id = ?", String.class, id)).isNull();
        assertThat(liens.findByClientSocieteId(id).orElseThrow().getPartenaireSocieteId()).isEqualTo(p);
        assertThat(estManagerDe(users.findByEmail(email).orElseThrow().getId(), id)).isTrue();
    }

    @Test
    void emailDUnUtilisateurExistantPourUnParticulierNEcritRienEtNeCreeAucuneDemande() {
        Long p = partenaire();
        long societes = jdbc.queryForObject("SELECT count(*) FROM societes_identites", Long.class);
        long liensAvant = liens.count();
        long demandesAvant = demandes.count();

        ResultatCreation r = annuaire.creerClient(new CreationClient(p, true, null, null, false, user.getEmail().toUpperCase(), "X Y"));

        assertThat(r.issue()).isEqualTo("EMAIL_DEJA_UTILISE");
        assertThat(r.societeId()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM societes_identites", Long.class)).isEqualTo(societes);
        assertThat(liens.count()).isEqualTo(liensAvant);
        assertThat(demandes.count()).isEqualTo(demandesAvant);
    }

    @Test
    void emailDejaUtiliseNEcritRienPourUneSocieteNonPlus() {
        Long p = partenaire();
        long societes = jdbc.queryForObject("SELECT count(*) FROM societes_identites", Long.class);

        ResultatCreation r = annuaire.creerClient(new CreationClient(p, false, sirenValide(11223344), null, false, user.getEmail(), "X Y"));

        assertThat(r.issue()).isEqualTo("EMAIL_DEJA_UTILISE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM societes_identites", Long.class)).isEqualTo(societes);
    }

    @Test
    void sirenDejaConnuEnregistreUneDemandeSansLienNiUtilisateur() {
        Long p = partenaire();
        String siren = sirenValide(99887766);
        Long existante = societe(siren, com.logicielpourtous.site.features.identite.NumerosSociete.tvaFrancaise(siren));
        String email = emailNeuf();
        long liensAvant = liens.count();

        ResultatCreation r = annuaire.creerClient(new CreationClient(p, false, siren, null, false, email, "Bob Durand"));

        assertThat(r.issue()).isEqualTo("DEJA_CONNUE");
        assertThat(r.societeId()).isNull();
        assertThat(liens.count()).isEqualTo(liensAvant);
        assertThat(users.findByEmail(email)).isEmpty();
        assertThat(appartenances.findBySocieteIdOrderById(existante)).isEmpty();
        List<DemandeSocieteConnue> ds = demandes.findAll().stream()
                .filter(d -> existante.equals(d.getSocieteIdentiteId())).toList();
        assertThat(ds).hasSize(1);
        assertThat(ds.get(0).isTraitee()).isFalse();
        assertThat(ds.get(0).getEmailDemandeur()).isEqualTo(email);
    }

    @Test
    void creerClientRefuseLesEntreesInvalides() {
        Long p = partenaire();
        assertThatThrownBy(() -> annuaire.creerClient(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> annuaire.creerClient(new CreationClient(null, true, null, null, false, emailNeuf(), "A")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> annuaire.creerClient(new CreationClient(p, true, null, null, false, " ", "A")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> annuaire.creerClient(new CreationClient(p, true, null, null, false, "pas-un-email", "A")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> annuaire.creerClient(new CreationClient(p, true, null, null, false, emailNeuf(), " ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> annuaire.creerClient(new CreationClient(p, false, "123", null, false, emailNeuf(), "A")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Entree du partenaire dans l'espace d'un client ----

    private Long partenaireQuiPeutEntrer(Long clientId) {
        Long p = societe("EN" + UUID.randomUUID().toString().substring(0, 6), "FREN" + UUID.randomUUID().toString().substring(0, 6));
        droits.fixerPlafond(p, Set.of("clients.entrer"));
        droits.accorderAuRole(p, "ROLE_GESTIONNAIRE", Set.of("clients.entrer"));
        droits.fixerPlafond(clientId, Set.of("clients.lire"));
        droits.accorderAuRole(clientId, "ROLE_GESTIONNAIRE", Set.of("clients.lire"));
        lier(clientId, p, TypeCapacite.CLIENT);
        return p;
    }

    @Test
    void entrerEspaceClientUtiliseLaSocieteDeLIdentiteEtRendLaPortee() {
        Long client = societe("EC1" + UUID.randomUUID().toString().substring(0, 5), "FREC1" + UUID.randomUUID().toString().substring(0, 5));
        Long p = partenaireQuiPeutEntrer(client);
        Appartenance a = appartenance(p);
        connecter(new CustomUserDetails(user, List.of(a), a.getId()));

        var portee = annuaire.entrerEspaceClient(user.getId(), client);

        assertThat(portee.societeId()).isEqualTo(client);
        assertThat(portee.droitsEffectifs()).containsExactly("clients.lire");
    }

    @Test
    void entrerEspaceClientRefuseSansIdentite() {
        Long client = societe("EC2" + UUID.randomUUID().toString().substring(0, 5), "FREC2" + UUID.randomUUID().toString().substring(0, 5));
        partenaireQuiPeutEntrer(client);

        assertThatThrownBy(() -> annuaire.entrerEspaceClient(user.getId(), client)).isInstanceOf(SecurityException.class);
    }

    @Test
    void entrerEspaceClientRefuseUnUserIdQuiNEstPasCeluiDeLIdentite() {
        Long client = societe("EC3" + UUID.randomUUID().toString().substring(0, 5), "FREC3" + UUID.randomUUID().toString().substring(0, 5));
        Long p = partenaireQuiPeutEntrer(client);
        Appartenance a = appartenance(p);
        connecter(new CustomUserDetails(user, List.of(a), a.getId()));

        assertThatThrownBy(() -> annuaire.entrerEspaceClient(user.getId() + 1, client)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> annuaire.entrerEspaceClient(null, client)).isInstanceOf(SecurityException.class);
    }

    @Test
    void entrerEspaceClientRefuseUneIdentiteSansSociete() {
        Long client = societe("EC4" + UUID.randomUUID().toString().substring(0, 5), "FREC4" + UUID.randomUUID().toString().substring(0, 5));
        partenaireQuiPeutEntrer(client);
        Appartenance a = appartenance(null);
        connecter(new CustomUserDetails(user, List.of(a), a.getId()));

        assertThatThrownBy(() -> annuaire.entrerEspaceClient(user.getId(), client)).isInstanceOf(SecurityException.class);
    }

    @Test
    void entrerEspaceClientRefuseUnClientDUnAutrePartenaire() {
        Long client = societe("EC5" + UUID.randomUUID().toString().substring(0, 5), "FREC5" + UUID.randomUUID().toString().substring(0, 5));
        partenaireQuiPeutEntrer(client);
        Long autre = societe("EC6" + UUID.randomUUID().toString().substring(0, 5), "FREC6" + UUID.randomUUID().toString().substring(0, 5));
        droits.fixerPlafond(autre, Set.of("clients.entrer"));
        droits.accorderAuRole(autre, "ROLE_GESTIONNAIRE", Set.of("clients.entrer"));
        Appartenance a = appartenance(autre);
        connecter(new CustomUserDetails(user, List.of(a), a.getId()));

        assertThatThrownBy(() -> annuaire.entrerEspaceClient(user.getId(), client)).isInstanceOf(SecurityException.class);
    }
}
