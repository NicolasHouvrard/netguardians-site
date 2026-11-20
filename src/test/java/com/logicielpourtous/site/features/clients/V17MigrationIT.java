package com.logicielpourtous.site.features.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Migration V17 : modele de droits par defaut, journal des actions du maitre, etat des partenaires. */
@SpringBootTest
@Transactional
class V17MigrationIT {

    @Autowired private JdbcTemplate jdbc;

    private int tables(String nom) {
        return jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?",
                Integer.class, nom);
    }

    @Test
    void lesTroisTablesExistent() {
        assertThat(tables("modele_droits_partenaire")).isEqualTo(1);
        assertThat(tables("journal_actions_maitre")).isEqualTo(1);
        assertThat(tables("partenaires_etat")).isEqualTo(1);
    }

    @Test
    void leModeleContientLesTroisDroitsDeDepart() {
        List<String> droits = jdbc.queryForList("SELECT droit FROM modele_droits_partenaire", String.class);
        assertThat(droits).containsExactlyInAnyOrder("clients.lire", "clients.creer", "clients.entrer");
    }

    @Test
    void laContrainteRefuseUnEtatInconnu() {
        Long id = jdbc.queryForObject("INSERT INTO societes_identites (siren, tva, type) VALUES ('V17A', 'FRV17A', 'PARTENAIRE') RETURNING id",
                Long.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO partenaires_etat (societe_id, etat, debut_validation) VALUES (?, 'AUTRE', now())", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void leJournalDesActionsDuMaitreNAAucuneCleEtrangere() {
        int fk = jdbc.queryForObject("SELECT count(*) FROM information_schema.table_constraints "
                + "WHERE table_schema = 'public' AND table_name = 'journal_actions_maitre' AND constraint_type = 'FOREIGN KEY'",
                Integer.class);
        assertThat(fk).isZero();
    }
}
