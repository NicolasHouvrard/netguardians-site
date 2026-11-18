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
        AnnuaireClientsContrat contrat = new AnnuaireClientsContratImpl(liens, capacites, null, faux);

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
}
