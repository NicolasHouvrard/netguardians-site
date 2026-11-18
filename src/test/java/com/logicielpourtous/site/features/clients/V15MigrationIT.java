package com.logicielpourtous.site.features.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;

/** Migration V15 : l'unicite des appartenances tient compte de la societe du registre. */
@SpringBootTest
@Transactional
class V15MigrationIT {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantRepository tenants;
    @Autowired private UserRepository users;

    private Long tenantId;
    private Long userId;
    private Long societe1;
    private Long societe2;

    @BeforeEach
    void preparer() {
        Tenant tenant = new Tenant();
        tenant.setName("V15 Tenant");
        tenant.setSchemaName("v15_tenant_" + UUID.randomUUID());
        tenant = tenants.save(tenant);
        tenantId = tenant.getId();
        User user = new User();
        user.setEmail("v15-" + UUID.randomUUID() + "@logicielpourtous.com");
        user.setPassword("test");
        user.setFullName("Test");
        user.setActive(true);
        user.setTenant(tenant);
        userId = users.saveAndFlush(user).getId();
        societe1 = societe("V15A", "FRV15A");
        societe2 = societe("V15B", "FRV15B");
    }

    private Long societe(String siren, String tva) {
        return jdbc.queryForObject("INSERT INTO societes_identites (siren, tva, type) VALUES (?, ?, 'CLIENT') RETURNING id",
                Long.class, siren, tva);
    }

    private void appartenir(Long societeId) {
        jdbc.update("INSERT INTO appartenances (user_id, tenant_id, societe_id) VALUES (?, ?, ?)",
                userId, tenantId, societeId);
    }

    @Test
    void unUtilisateurPeutAppartenirADeuxSocietesDuMemeTenant() {
        appartenir(societe1);
        appartenir(societe2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM appartenances WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(2);
    }

    @Test
    void unUtilisateurNePeutAppartenirDeuxFoisALaMemeSociete() {
        appartenir(societe1);
        assertThatThrownBy(() -> appartenir(societe1)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
