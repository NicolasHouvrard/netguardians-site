package com.logicielpourtous.site.features.clients;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Le journal des actions du maitre s'ecrit dans sa propre transaction. */
@SpringBootTest
@Transactional
class JournalActionsMaitreTest {

    private static final long ADMIN = 987_654_321L;

    @Autowired private JournalActionsMaitre journal;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager tm;

    private TransactionTemplate autreTransaction() {
        TransactionTemplate tx = new TransactionTemplate(tm);
        tx.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
        return tx;
    }

    @AfterEach
    void nettoyer() {
        autreTransaction().executeWithoutResult(
                s -> jdbc.update("DELETE FROM journal_actions_maitre WHERE admin_user_id = ?", ADMIN));
    }

    @Test
    void laLigneEstVisibleDepuisUneAutreTransactionPendantQueLAppelanteResteOuverte() {
        journal.enregistrer(ADMIN, "PARTENAIRE_CREE", 11L, null);

        List<Map<String, Object>> lignes = autreTransaction().execute(s -> jdbc.queryForList(
                "SELECT action, partenaire_societe_id, objet_societe_id, cree_a FROM journal_actions_maitre "
                        + "WHERE admin_user_id = ?", ADMIN));
        assertThat(lignes).hasSize(1);
        assertThat(lignes.get(0)).containsEntry("action", "PARTENAIRE_CREE").containsEntry("partenaire_societe_id", 11L);
        assertThat(lignes.get(0).get("objet_societe_id")).isNull();
        assertThat(lignes.get(0).get("cree_a")).isNotNull();
    }
}
