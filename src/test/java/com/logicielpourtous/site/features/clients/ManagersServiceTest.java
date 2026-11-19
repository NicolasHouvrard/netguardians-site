package com.logicielpourtous.site.features.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.role.RoleRepository;
import com.logicielpourtous.site.features.user.Appartenance;
import com.logicielpourtous.site.features.user.AppartenanceRepository;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;

/** Regles des managers : une societe a toujours au moins un manager ; le premier utilisateur en est un. */
@SpringBootTest
@Transactional
class ManagersServiceTest {

    private static final String MANAGER = ManagersService.ROLE_MANAGER;

    @Autowired private ManagersService managers;
    @Autowired private AppartenanceRepository appartenances;
    @Autowired private TenantRepository tenants;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private JdbcTemplate jdbc;

    private Long societe;
    private Tenant tenant;
    private User u1;
    private User u2;
    private User u3;

    @BeforeEach
    void preparer() {
        societe = societe("MG1", "FRMG1", null);
        tenant = tenant();
        u1 = user("u1");
        u2 = user("u2");
        u3 = user("u3");
    }

    private Tenant tenant() {
        Tenant t = new Tenant();
        t.setName("Test Tenant");
        t.setSchemaName("test_tenant_" + UUID.randomUUID());
        return tenants.save(t);
    }

    private Long societe(String siren, String tva, Long proprietaireTenant) {
        return jdbc.queryForObject("INSERT INTO societes_identites (siren, tva, type, proprietaire_tenant_id) "
                + "VALUES (?, ?, 'CLIENT', ?) RETURNING id", Long.class, siren, tva, proprietaireTenant);
    }

    private User user(String prefixe) {
        User u = new User();
        u.setEmail(prefixe + "-" + UUID.randomUUID() + "@logicielpourtous.com");
        u.setPassword("test");
        u.setFullName("Test");
        u.setActive(true);
        u.setTenant(tenant);
        return users.saveAndFlush(u);
    }

    private Role role(String nom) {
        return roles.findByName(nom).orElseGet(() -> roles.save(new Role(nom, nom, "CLIENT")));
    }

    private boolean estManager(User u) {
        return appartenances.findByUserIdAndSocieteId(u.getId(), societe).stream()
                .anyMatch(a -> a.getRoles().stream().anyMatch(r -> MANAGER.equals(r.getName())));
    }

    @Test
    void leRoleManagerEstGestionnaire() {
        assertThat(MANAGER).isEqualTo("ROLE_GESTIONNAIRE");
        assertThat(roles.findByName(MANAGER)).isPresent();
    }

    @Test
    void lePremierUtilisateurEstManagerMemeSansRoleDemande() {
        Appartenance a = managers.ajouterUtilisateur(u1.getId(), societe, Set.of());
        assertThat(a.getSocieteId()).isEqualTo(societe);
        assertThat(estManager(u1)).isTrue();
    }

    @Test
    void leDeuxiemeUtilisateurNEstPasManager() {
        managers.ajouterUtilisateur(u1.getId(), societe, Set.of());
        managers.ajouterUtilisateur(u2.getId(), societe, Set.of());
        assertThat(estManager(u2)).isFalse();
    }

    @Test
    void lesRolesDemandesSontConserves() {
        role("ROLE_LECTEUR_MANAGERS_TEST");
        managers.ajouterUtilisateur(u1.getId(), societe, Set.of());
        managers.ajouterUtilisateur(u2.getId(), societe, Set.of("ROLE_LECTEUR_MANAGERS_TEST"));
        assertThat(appartenances.findByUserIdAndSocieteId(u2.getId(), societe).get(0).getRoles())
                .extracting(Role::getName).containsExactly("ROLE_LECTEUR_MANAGERS_TEST");
    }

    @Test
    void retirerLeRoleDuSeulManagerEstRefuse() {
        managers.ajouterUtilisateur(u1.getId(), societe, Set.of());
        managers.ajouterUtilisateur(u2.getId(), societe, Set.of());

        assertThatThrownBy(() -> managers.retirerRoleManager(u1.getId(), societe))
                .isInstanceOf(DernierManagerException.class);
        assertThat(estManager(u1)).isTrue();
    }

    @Test
    void avecDeuxManagersOnPeutEnRetirerUn() {
        managers.ajouterUtilisateur(u1.getId(), societe, Set.of());
        managers.ajouterUtilisateur(u2.getId(), societe, Set.of(MANAGER));

        managers.retirerRoleManager(u1.getId(), societe);

        assertThat(estManager(u1)).isFalse();
        assertThat(estManager(u2)).isTrue();
    }

    @Test
    void societeAUnSeulUtilisateurSonStatutDeManagerNePeutEtreRetire() {
        managers.ajouterUtilisateur(u1.getId(), societe, Set.of());

        assertThatThrownBy(() -> managers.retirerRoleManager(u1.getId(), societe))
                .isInstanceOf(DernierManagerException.class);
        assertThat(estManager(u1)).isTrue();
    }

    @Test
    void supprimerLeSeulManagerParSuppressionDeLUtilisateurEstRefuse() {
        managers.ajouterUtilisateur(u1.getId(), societe, Set.of());
        managers.ajouterUtilisateur(u2.getId(), societe, Set.of());

        assertThatThrownBy(() -> managers.retirerUtilisateur(u1.getId(), societe))
                .isInstanceOf(DernierManagerException.class);
        assertThat(appartenances.findByUserIdAndSocieteId(u1.getId(), societe)).hasSize(1);
    }

    @Test
    void supprimerLeSeulUtilisateurManagerEstRefuse() {
        managers.ajouterUtilisateur(u1.getId(), societe, Set.of());

        assertThatThrownBy(() -> managers.retirerUtilisateur(u1.getId(), societe))
                .isInstanceOf(DernierManagerException.class);
    }

    @Test
    void supprimerUnNonManagerOuUnManagerParmiDeuxPasse() {
        managers.ajouterUtilisateur(u1.getId(), societe, Set.of());
        managers.ajouterUtilisateur(u2.getId(), societe, Set.of());
        managers.ajouterUtilisateur(u3.getId(), societe, Set.of(MANAGER));

        managers.retirerUtilisateur(u2.getId(), societe);
        managers.retirerUtilisateur(u1.getId(), societe);

        assertThat(appartenances.findByUserIdAndSocieteId(u2.getId(), societe)).isEmpty();
        assertThat(appartenances.findByUserIdAndSocieteId(u1.getId(), societe)).isEmpty();
        assertThat(estManager(u3)).isTrue();
    }

    @Test
    void lesAppartenancesDUneAutreSocieteNeCompterntPas() {
        Long autre = societe("MG2", "FRMG2", null);
        managers.ajouterUtilisateur(u1.getId(), autre, Set.of());
        managers.ajouterUtilisateur(u2.getId(), societe, Set.of());

        assertThat(estManager(u2)).isTrue();
    }

    @Test
    void entreesInvalidesSontRefuseesSansRienEcrire() {
        long avant = appartenances.count();
        assertThatThrownBy(() -> managers.ajouterUtilisateur(null, societe, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> managers.ajouterUtilisateur(u1.getId(), null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> managers.ajouterUtilisateur(u1.getId(), -1L, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> managers.ajouterUtilisateur(-1L, societe, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> managers.ajouterUtilisateur(u1.getId(), societe, Set.of("ROLE_INCONNU_XYZ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> managers.retirerRoleManager(null, societe))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> managers.retirerUtilisateur(u1.getId(), -1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(appartenances.count()).isEqualTo(avant);
    }

    @Test
    void unUtilisateurDejaMembreNEstPasAjouteDeuxFois() {
        managers.ajouterUtilisateur(u1.getId(), societe, Set.of());
        assertThatThrownBy(() -> managers.ajouterUtilisateur(u1.getId(), societe, Set.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void retirerLeRoleManagerDUnNonManagerEstRefuse() {
        managers.ajouterUtilisateur(u1.getId(), societe, Set.of());
        managers.ajouterUtilisateur(u2.getId(), societe, Set.of());
        assertThatThrownBy(() -> managers.retirerRoleManager(u2.getId(), societe))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void leTenantEstCeluiDeLaSocieteQuandIlEstConnu() {
        Tenant proprio = tenant();
        Long s = societe("MG3", "FRMG3", proprio.getId());
        Appartenance a = managers.ajouterUtilisateur(u1.getId(), s, Set.of());
        assertThat(a.getTenant().getId()).isEqualTo(proprio.getId());
    }

    @Test
    void sansTenantDeSocieteOnRetombeSurLeTenantDeLUtilisateur() {
        Appartenance a = managers.ajouterUtilisateur(u1.getId(), societe, Set.of());
        assertThat(a.getTenant().getId()).isEqualTo(tenant.getId());
    }

    @Test
    void unRoleQuiNEstPasUnRoleClientEstRefuseSansRienEcrire() {
        roles.findByName("ROLE_ADMIN_MANAGERS_TEST").orElseGet(() -> roles.save(new Role("ROLE_ADMIN_MANAGERS_TEST", "A", "ESN")));
        roles.findByName("ROLE_SANS_TYPE_MANAGERS_TEST").orElseGet(() -> roles.save(new Role("ROLE_SANS_TYPE_MANAGERS_TEST", "S", null)));

        assertThatThrownBy(() -> managers.ajouterUtilisateur(u1.getId(), societe, Set.of("ROLE_ADMIN_MANAGERS_TEST")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> managers.ajouterUtilisateur(u1.getId(), societe, Set.of("ROLE_SANS_TYPE_MANAGERS_TEST")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> managers.ajouterUtilisateur(u1.getId(), societe, Set.of("ROLE_ADMIN")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(appartenances.findByUserIdAndSocieteId(u1.getId(), societe)).isEmpty();
    }
}
