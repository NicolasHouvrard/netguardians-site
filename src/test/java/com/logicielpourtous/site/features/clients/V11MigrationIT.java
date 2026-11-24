package com.logicielpourtous.site.features.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Migration V11 : capacites de societe et lien client-partenaire. Le script est rejoue (il est idempotent) apres
 * avoir insere des societes d'anciens types, pour verifier la reprise de {@code societes_identites.type}.
 */
@SpringBootTest
@Transactional
class V11MigrationIT {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private CapaciteSocieteRepository capacites;

    private Long partenaire;
    private Long fournisseur;
    private Long client;

    @BeforeEach
    void preparer() {
        partenaire = societe("V11P", "FRV11P", "PARTENAIRE");
        fournisseur = societe("V11F", "FRV11F", "FOURNISSEUR");
        client = societe("V11C", "FRV11C", "CLIENT");
        rejouerMigration();
    }

    private Long societe(String siren, String tva, String type) {
        return jdbc.queryForObject("INSERT INTO societes_identites (siren, tva, type) VALUES (?, ?, ?) RETURNING id",
                Long.class, siren, tva, type);
    }

    private void rejouerMigration() {
        try {
            String sql = new String(new ClassPathResource("db/migration/V11__capacites_clients.sql")
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            jdbc.execute(sql);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> capacitesDe(Long societeId) {
        return jdbc.queryForList(
                "SELECT capacite FROM societe_capacites WHERE societe_id = ? ORDER BY capacite", String.class, societeId);
    }

    @Test
    void unPartenaireRecoitAussiLaCapaciteClient() {
        assertThat(capacitesDe(partenaire)).containsExactly("CLIENT", "PARTENAIRE");
        assertThat(capacites.existsBySocieteIdAndCapacite(partenaire, TypeCapacite.CLIENT)).isTrue();
        assertThat(capacites.findBySocieteId(partenaire)).hasSize(2);
    }

    @Test
    void unFournisseurNAQueLaCapaciteFournisseur() {
        assertThat(capacitesDe(fournisseur)).containsExactly("FOURNISSEUR");
    }

    @Test
    void unClientNAQueLaCapaciteClient() {
        assertThat(capacitesDe(client)).containsExactly("CLIENT");
    }

    @Test
    void rejouerLaMigrationNeDuppliquePasLesCapacites() {
        rejouerMigration();
        assertThat(capacitesDe(partenaire)).containsExactly("CLIENT", "PARTENAIRE");
    }

    @Test
    void laColonneTypeEstDevenueNullable() {
        Long id = jdbc.queryForObject(
                "INSERT INTO societes_identites (siren, tva) VALUES ('V11N', 'FRV11N') RETURNING id", Long.class);
        assertThat(id).isNotNull();
    }

    @Test
    void uneMemeCapaciteNePeutEtreInsereeDeuxFois() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO societe_capacites (societe_id, capacite) VALUES (?, 'FOURNISSEUR')", fournisseur))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void liensClientsRefuseUnClientSocieteIdEnDouble() {
        jdbc.update("INSERT INTO liens_clients (client_societe_id, partenaire_societe_id, origine) "
                + "VALUES (?, ?, 'PARTENAIRE')", client, partenaire);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO liens_clients (client_societe_id, partenaire_societe_id, "
                + "origine) VALUES (?, ?, 'INSCRIPTION')", client, partenaire))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
