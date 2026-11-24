package com.logicielpourtous.site.features.commun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creation transactionnelle d'un tenant et de son schema. Volontairement SANS transaction de test : la transaction a
 * verifier est celle de l'implementation, validee ou annulee pour de bon.
 */
@SpringBootTest
class TenantTransactionnelTest {

    @Autowired private TenantManagementService service;
    @Autowired private TenantRepository tenants;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private final List<String> schemas = new ArrayList<>();

    @AfterEach
    void nettoyer() {
        for (String schema : schemas) {
            jdbc.update("DELETE FROM tenants WHERE schema_name = ?", schema);
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private boolean schemaExiste(String schema) {
        return jdbc.queryForObject("SELECT count(*) FROM information_schema.schemata WHERE schema_name = ?",
                Long.class, schema) > 0;
    }

    @Test
    void creerTenantTransactionnelCreeLeTenantEtLeSchema() {
        String schema = TenantManagementService.nouveauNomDeSchema();
        schemas.add(schema);

        Tenant tenant = service.creerTenantTransactionnel("Partenaire Test", schema);

        assertThat(tenant.getId()).isNotNull();
        assertThat(tenants.findById(tenant.getId())).isPresent();
        assertThat(schemaExiste(schema)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = 'contacts'",
                Long.class, schema)).isEqualTo(1L);
    }

    @Test
    void nomDeSchemaHostileEstRefuseSansRienEcrire() {
        long avant = jdbc.queryForObject("SELECT count(*) FROM tenants", Long.class);
        for (String hostile : new String[] {"p_abc; DROP SCHEMA public", "public", "P_ABCDEF012345"}) {
            assertThatThrownBy(() -> service.creerTenantTransactionnel("Hostile", hostile))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenants", Long.class)).isEqualTo(avant);
    }

    @Test
    void echecApresLaCreationAnnuleLeSchemaEtLeTenant() {
        String schema = TenantManagementService.nouveauNomDeSchema();
        schemas.add(schema);
        long avant = jdbc.queryForObject("SELECT count(*) FROM tenants", Long.class);

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.creerTenantTransactionnel("Annule", schema);
            throw new IllegalStateException("echec apres creation");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(schemaExiste(schema)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenants WHERE schema_name = ?", Long.class, schema))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenants", Long.class)).isEqualTo(avant);
    }

    @Test
    void nouveauNomDeSchemaRespecteLeFormat() {
        Set<String> noms = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            String nom = TenantManagementService.nouveauNomDeSchema();
            assertThat(nom).matches("^p_[0-9a-f]{12}$");
            noms.add(nom);
        }
        assertThat(noms).hasSize(50);
    }
}
