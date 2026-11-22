package com.logicielpourtous.site.features.theme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Atomicite : si l'ecriture du journal echoue, le reglage n'est pas ecrit non plus (meme transaction). */
@SpringBootTest
class ThemeServiceAtomiciteTest {

    @Autowired private ThemeService service;
    @Autowired private ThemeReglageRepository reglages;
    @MockitoSpyBean private ThemeJournalRepository journal;
    @Autowired private JdbcTemplate jdbc;

    private Map<String, String> sauvegarde;

    @BeforeEach
    void sauver() {
        sauvegarde = new HashMap<>();
        jdbc.query("SELECT cle, valeur FROM theme_reglages", rs -> { sauvegarde.put(rs.getString(1), rs.getString(2)); });
        jdbc.update("DELETE FROM theme_reglages");
    }

    @AfterEach
    void restaurer() {
        jdbc.update("DELETE FROM theme_journal WHERE modifie_par = 910003");
        jdbc.update("DELETE FROM theme_reglages");
        sauvegarde.forEach((c, v) -> jdbc.update("INSERT INTO theme_reglages (cle, valeur) VALUES (?, ?)", c, v));
    }

    @Test
    void pannePendantLeJournal_annuleLeReglage() {
        doThrow(new IllegalStateException("panne")).when(journal).save(any());
        assertThatThrownBy(() -> service.definir(910003L, "couleur-fond", "#010203")).isInstanceOf(IllegalStateException.class);
        assertThat(reglages.count()).isZero();
    }
}
