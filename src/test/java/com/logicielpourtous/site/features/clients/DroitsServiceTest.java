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

/** Droits a trois couches : plafond du partenaire, reglages du manager, roles de l'utilisateur. */
@SpringBootTest
@Transactional
class DroitsServiceTest {

    private static final String LIRE = "clients.lire";
    private static final String ECRIRE = "clients.ecrire";

    @Autowired private DroitsService droits;
    @Autowired private PlafondClientRepository plafonds;
    @Autowired private ReglageManagerRepository reglages;
    @Autowired private AppartenanceRepository appartenances;
    @Autowired private TenantRepository tenants;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private JdbcTemplate jdbc;

    private Long societeA;
    private Long societeB;
    private Role gestionnaire;
    private Role lecteur;
    private User user;
    private Tenant tenant;

    @BeforeEach
    void preparer() {
        societeA = societe("DR1", "FRDR1");
        societeB = societe("DR2", "FRDR2");
        gestionnaire = role("ROLE_GESTIONNAIRE");
        lecteur = role("ROLE_LECTEUR_DROITS_TEST");
        tenant = new Tenant();
        tenant.setName("Test Tenant");
        tenant.setSchemaName("test_tenant_" + UUID.randomUUID());
        tenant = tenants.save(tenant);
        user = new User();
        user.setEmail("droits-" + UUID.randomUUID() + "@logicielpourtous.com");
        user.setPassword("test");
        user.setFullName("Test");
        user.setActive(true);
        user.setTenant(tenant);
        user = users.saveAndFlush(user);
    }

    private Long societe(String siren, String tva) {
        return jdbc.queryForObject("INSERT INTO societes_identites (siren, tva, type) VALUES (?, ?, 'CLIENT') RETURNING id",
                Long.class, siren, tva);
    }

    private Role role(String nom) {
        return roles.findByName(nom).orElseGet(() -> roles.save(new Role(nom, nom, "ESN")));
    }

    private void appartenir(Long societeId, Role... rolesDetenus) {
        Appartenance a = new Appartenance();
        a.setUserId(user.getId());
        a.setTenant(tenant);
        a.setSocieteId(societeId);
        a.setRoles(Set.of(rolesDetenus));
        appartenances.saveAndFlush(a);
    }

    @Test
    void accorderUnDroitHorsPlafondLeveEtNEcritRien() {
        droits.fixerPlafond(societeA, Set.of(LIRE));

        assertThatThrownBy(() -> droits.accorderAuRole(societeA, gestionnaire.getName(), Set.of(LIRE, ECRIRE)))
                .isInstanceOf(DepassePlafondException.class)
                .hasMessageContaining(ECRIRE);

        assertThat(reglages.findByClientSocieteIdAndRoleNom(societeA, gestionnaire.getName())).isEmpty();
    }

    @Test
    void unRefusHorsPlafondNeToucheJamaisLesReglagesExistants() {
        droits.fixerPlafond(societeA, Set.of(LIRE));
        droits.accorderAuRole(societeA, gestionnaire.getName(), Set.of(LIRE));

        assertThatThrownBy(() -> droits.accorderAuRole(societeA, gestionnaire.getName(), Set.of(ECRIRE)))
                .isInstanceOf(DepassePlafondException.class);

        assertThat(reglages.findByClientSocieteIdAndRoleNom(societeA, gestionnaire.getName()))
                .extracting(ReglageManager::getDroit).containsExactly(LIRE);
    }

    @Test
    void baisserLePlafondRetireLeDroitMaisLeReglageResteStocke() {
        droits.fixerPlafond(societeA, Set.of(LIRE, ECRIRE));
        droits.accorderAuRole(societeA, gestionnaire.getName(), Set.of(LIRE, ECRIRE));
        appartenir(societeA, gestionnaire);
        assertThat(droits.autorise(user.getId(), societeA, ECRIRE)).isTrue();

        droits.fixerPlafond(societeA, Set.of(LIRE));

        assertThat(droits.autorise(user.getId(), societeA, ECRIRE)).isFalse();
        assertThat(droits.autorise(user.getId(), societeA, LIRE)).isTrue();
        assertThat(reglages.findByClientSocieteIdAndRoleNom(societeA, gestionnaire.getName()))
                .extracting(ReglageManager::getDroit).containsExactlyInAnyOrder(LIRE, ECRIRE);
    }

    @Test
    void sansAppartenanceDansLaSocieteOnEstRefuse() {
        droits.fixerPlafond(societeA, Set.of(LIRE));
        droits.accorderAuRole(societeA, gestionnaire.getName(), Set.of(LIRE));

        assertThat(droits.autorise(user.getId(), societeA, LIRE)).isFalse();
    }

    @Test
    void deuxRolesDontUnSeulAccordeSuffit() {
        droits.fixerPlafond(societeA, Set.of(LIRE));
        droits.accorderAuRole(societeA, gestionnaire.getName(), Set.of(LIRE));
        appartenir(societeA, lecteur, gestionnaire);

        assertThat(droits.autorise(user.getId(), societeA, LIRE)).isTrue();
    }

    @Test
    void deuxRolesAucunAccordeRefuse() {
        droits.fixerPlafond(societeA, Set.of(LIRE));
        appartenir(societeA, lecteur, gestionnaire);

        assertThat(droits.autorise(user.getId(), societeA, LIRE)).isFalse();
    }

    @Test
    void lAppartenanceALaSocieteANeDonneRienSurLaSocieteB() {
        droits.fixerPlafond(societeA, Set.of(LIRE));
        droits.accorderAuRole(societeA, gestionnaire.getName(), Set.of(LIRE));
        droits.fixerPlafond(societeB, Set.of(LIRE));
        droits.accorderAuRole(societeB, gestionnaire.getName(), Set.of(LIRE));
        appartenir(societeA, gestionnaire);

        assertThat(droits.autorise(user.getId(), societeA, LIRE)).isTrue();
        assertThat(droits.autorise(user.getId(), societeB, LIRE)).isFalse();
    }

    @Test
    void lAccordDuManagerDansUneSocieteNeValentPasDansUneAutre() {
        droits.fixerPlafond(societeA, Set.of(LIRE));
        droits.fixerPlafond(societeB, Set.of(LIRE));
        droits.accorderAuRole(societeB, gestionnaire.getName(), Set.of(LIRE));
        appartenir(societeA, gestionnaire);

        assertThat(droits.autorise(user.getId(), societeA, LIRE)).isFalse();
    }

    @Test
    void sansPlafondToutEstRefuse() {
        appartenir(societeA, gestionnaire);
        reglages.saveAndFlush(new ReglageManager(societeA, gestionnaire.getName(), LIRE));

        assertThat(droits.autorise(user.getId(), societeA, LIRE)).isFalse();
    }

    @Test
    void accorderSansPlafondLeveDepassePlafond() {
        assertThatThrownBy(() -> droits.accorderAuRole(societeA, gestionnaire.getName(), Set.of(LIRE)))
                .isInstanceOf(DepassePlafondException.class);
    }

    @Test
    void accorderUnEnsembleVideRetireLesReglagesDuRole() {
        droits.fixerPlafond(societeA, Set.of(LIRE));
        droits.accorderAuRole(societeA, gestionnaire.getName(), Set.of(LIRE));

        droits.accorderAuRole(societeA, gestionnaire.getName(), Set.of());

        assertThat(reglages.findByClientSocieteIdAndRoleNom(societeA, gestionnaire.getName())).isEmpty();
    }

    @Test
    void fixerPlafondRemplaceLEnsemble() {
        droits.fixerPlafond(societeA, Set.of(LIRE));
        droits.fixerPlafond(societeA, Set.of(ECRIRE));

        assertThat(plafonds.findByClientSocieteId(societeA)).extracting(PlafondClient::getDroit)
                .containsExactly(ECRIRE);
    }

    @Test
    void entreesNullesOuVidesRejeteesAvantToutEcriture() {
        droits.fixerPlafond(societeA, Set.of(LIRE));
        droits.accorderAuRole(societeA, gestionnaire.getName(), Set.of(LIRE));
        Set<String> avecNul = new java.util.HashSet<>(Set.of(ECRIRE));
        avecNul.add(null);

        assertThatThrownBy(() -> droits.fixerPlafond(null, Set.of(LIRE))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> droits.fixerPlafond(societeA, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> droits.fixerPlafond(societeA, avecNul)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> droits.fixerPlafond(societeA, Set.of(" "))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> droits.accorderAuRole(null, "R", Set.of(LIRE))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> droits.accorderAuRole(societeA, null, Set.of(LIRE))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> droits.accorderAuRole(societeA, " ", Set.of(LIRE))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> droits.accorderAuRole(societeA, gestionnaire.getName(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> droits.accorderAuRole(societeA, gestionnaire.getName(), avecNul))
                .isInstanceOf(IllegalArgumentException.class);

        // Rien n'a ete modifie par les appels rejetes.
        assertThat(plafonds.findByClientSocieteId(societeA)).extracting(PlafondClient::getDroit).containsExactly(LIRE);
        assertThat(reglages.findByClientSocieteIdAndRoleNom(societeA, gestionnaire.getName()))
                .extracting(ReglageManager::getDroit).containsExactly(LIRE);
    }

    @Test
    void autoriseRenvoieFauxSansLever() {
        droits.fixerPlafond(societeA, Set.of(LIRE));
        droits.accorderAuRole(societeA, gestionnaire.getName(), Set.of(LIRE));
        appartenir(societeA, gestionnaire);

        assertThat(droits.autorise(null, societeA, LIRE)).isFalse();
        assertThat(droits.autorise(user.getId(), null, LIRE)).isFalse();
        assertThat(droits.autorise(user.getId(), societeA, null)).isFalse();
        assertThat(droits.autorise(user.getId(), societeA, " ")).isFalse();
        assertThat(droits.autorise(user.getId(), societeA, "clients.inconnu")).isFalse();
    }
}
