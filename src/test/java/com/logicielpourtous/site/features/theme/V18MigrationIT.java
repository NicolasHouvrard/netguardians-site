package com.logicielpourtous.site.features.theme;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Migration V18 : tables des reglages de theme et de leur journal. */
@SpringBootTest
class V18MigrationIT {

    @Autowired private JdbcTemplate jdbc;

    private List<String> colonnes(String table) {
        return jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' "
                + "AND table_name = ? ORDER BY ordinal_position", String.class, table);
    }

    @Test
    void lesTablesOntLesBonnesColonnes() {
        assertThat(colonnes("theme_reglages")).containsExactly("cle", "valeur", "modifie_par", "modifie_a");
        assertThat(colonnes("theme_journal")).containsExactly("id", "cle", "ancienne_valeur", "nouvelle_valeur", "modifie_par", "cree_a");
    }

    @Test
    void leJournalNAAucuneCleEtrangere() {
        int fk = jdbc.queryForObject("SELECT count(*) FROM information_schema.table_constraints "
                + "WHERE table_schema = 'public' AND table_name IN ('theme_journal', 'theme_reglages') "
                + "AND constraint_type = 'FOREIGN KEY'", Integer.class);
        assertThat(fk).isZero();
    }
}
