package com.logicielpourtous.site.features.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.role.Role;
import com.logicielpourtous.site.features.role.RoleRepository;

import jakarta.persistence.EntityManager;

/**
 * Modele des appartenances : une personne (un utilisateur) peut appartenir a plusieurs societes,
 * chaque appartenance portant son tenant, sa fiche client et ses roles.
 */
@SpringBootTest
@Transactional
class AppartenanceTest {

    @Autowired
    private AppartenanceRepository appartenanceRepository;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private EntityManager em;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void appartenance_conserveTenantFicheEtRoles() {
        Tenant tenant = nouveauTenant();
        User user = nouvelUtilisateur(tenant);
        Role role = unRole();

        Appartenance a = new Appartenance();
        a.setUserId(user.getId());
        a.setTenant(tenant);
        a.setCustomerId(5L);
        a.setRoles(Set.of(role));
        Long id = appartenanceRepository.saveAndFlush(a).getId();
        em.clear();

        Appartenance relue = appartenanceRepository.findById(id).orElseThrow();
        assertThat(relue.getTenant().getId()).isEqualTo(tenant.getId());
        assertThat(relue.getCustomerId()).isEqualTo(5L);
        assertThat(relue.getRoles()).extracting(Role::getId).containsExactly(role.getId());
        assertThat(relue.getCreeA()).isNotNull();
    }

    @Test
    void deuxAppartenancesDeLaMemePersonneSurDesSocietesDifferentesCoexistent() {
        User user = nouvelUtilisateur(nouveauTenant());
        Tenant t1 = nouveauTenant();
        Tenant t2 = nouveauTenant();

        appartenanceRepository.saveAndFlush(appartenance(user, t1, 1L));
        appartenanceRepository.saveAndFlush(appartenance(user, t2, 2L));
        em.clear();

        assertThat(appartenanceRepository.findByUserIdOrderById(user.getId()))
                .extracting(x -> x.getTenant().getId())
                .containsExactly(t1.getId(), t2.getId());
    }

    @Test
    void laBaseRefuseLaMemeAppartenanceEnDouble() {
        Tenant tenant = nouveauTenant();
        User user = nouvelUtilisateur(tenant);
        String sql = "INSERT INTO appartenances (user_id, tenant_id, customer_id) VALUES (?, ?, ?)";

        jdbc.update(sql, user.getId(), tenant.getId(), 7L);
        assertThatThrownBy(() -> jdbc.update(sql, user.getId(), tenant.getId(), 7L))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void laBaseRefuseLaMemeAppartenanceEnDoubleMemeSansFiche() {
        Tenant tenant = nouveauTenant();
        User user = nouvelUtilisateur(tenant);
        String sql = "INSERT INTO appartenances (user_id, tenant_id, customer_id) VALUES (?, ?, ?)";

        jdbc.update(sql, user.getId(), tenant.getId(), null);
        assertThatThrownBy(() -> jdbc.update(sql, user.getId(), tenant.getId(), null))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void findByUserId_neRenvoieQueLesAppartenancesDeCettePersonne() {
        Tenant tenant = nouveauTenant();
        User moi = nouvelUtilisateur(tenant);
        User autre = nouvelUtilisateur(tenant);
        appartenanceRepository.saveAndFlush(appartenance(moi, tenant, 1L));
        appartenanceRepository.saveAndFlush(appartenance(autre, tenant, 2L));
        em.clear();

        List<Appartenance> mes = appartenanceRepository.findByUserIdOrderById(moi.getId());
        assertThat(mes).hasSize(1);
        assertThat(mes.get(0).getUserId()).isEqualTo(moi.getId());
    }

    private Appartenance appartenance(User user, Tenant tenant, Long customerId) {
        Appartenance a = new Appartenance();
        a.setUserId(user.getId());
        a.setTenant(tenant);
        a.setCustomerId(customerId);
        return a;
    }

    private Tenant nouveauTenant() {
        Tenant tenant = new Tenant();
        tenant.setName("Test Tenant");
        tenant.setSchemaName("test_tenant_" + UUID.randomUUID());
        return tenantRepository.save(tenant);
    }

    private User nouvelUtilisateur(Tenant tenant) {
        User user = new User();
        user.setEmail("appartenance-" + UUID.randomUUID() + "@logicielpourtous.com");
        user.setPassword("test");
        user.setFullName("Test");
        user.setActive(true);
        user.setTenant(tenant);
        return userRepository.saveAndFlush(user);
    }

    private Role unRole() {
        return roleRepository.findByName("ROLE_ADMIN")
                .orElseGet(() -> roleRepository.save(new Role("ROLE_ADMIN", "Admin", "ESN")));
    }
}
