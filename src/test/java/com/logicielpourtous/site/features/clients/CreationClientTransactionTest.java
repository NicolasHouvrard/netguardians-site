package com.logicielpourtous.site.features.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicielpourtous.site.sdk.AnnuaireClientsContrat;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.CreationClient;

/**
 * Atomicite de la creation d'un client. Volontairement SANS transaction de test : la transaction a verifier est
 * celle de l'implementation, validee ou annulee pour de bon.
 */
@SpringBootTest
class CreationClientTransactionTest {

    @Autowired private AnnuaireClientsContrat annuaire;
    @Autowired private JdbcTemplate jdbc;

    private Long partenaire;

    @AfterEach
    void nettoyer() {
        if (partenaire != null) {
            jdbc.update("DELETE FROM societe_capacites WHERE societe_id = ?", partenaire);
            jdbc.update("DELETE FROM societes_identites WHERE id = ?", partenaire);
        }
    }

    @Test
    void unEchecAMiParcoursAnnuleToutSocieteComprise() {
        String suffixe = UUID.randomUUID().toString().substring(0, 8);
        partenaire = jdbc.queryForObject(
                "INSERT INTO societes_identites (siren, tva, type) VALUES (?, ?, 'CLIENT') RETURNING id", Long.class,
                "TX" + suffixe, "FRTX" + suffixe);
        jdbc.update("INSERT INTO societe_capacites (societe_id, capacite) VALUES (?, 'PARTENAIRE')", partenaire);
        long societes = jdbc.queryForObject("SELECT count(*) FROM societes_identites", Long.class);
        long liens = jdbc.queryForObject("SELECT count(*) FROM liens_clients", Long.class);
        long capacites = jdbc.queryForObject("SELECT count(*) FROM societe_capacites", Long.class);
        // Adresse trop longue pour la colonne users.email : l'ecriture de l'utilisateur echoue APRES societe + lien.
        String emailTropLong = "x".repeat(300) + "@example.test";

        assertThatThrownBy(() -> annuaire.creerClient(new CreationClient(partenaire, true, null, null, false,
                emailTropLong, "Zoe Test")))
                .isInstanceOf(RuntimeException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM societes_identites", Long.class)).isEqualTo(societes);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM liens_clients", Long.class)).isEqualTo(liens);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM societe_capacites", Long.class)).isEqualTo(capacites);
    }
}
