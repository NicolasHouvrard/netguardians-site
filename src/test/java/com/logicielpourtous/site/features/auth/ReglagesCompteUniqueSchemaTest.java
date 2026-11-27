package com.logicielpourtous.site.features.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicielpourtous.site.features.commun.ConfigurationCompteUnique;

/** Schema V20 (ddl-auto=validate) et ecriture reelle : le secret est chiffre dans la colonne. */
@SpringBootTest
class ReglagesCompteUniqueSchemaTest {

    @Autowired
    private ConfigurationCompteUniqueImpl config;

    @Autowired
    private ConfigurationCompteUnique contrat;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void nettoyer() {
        jdbc.update("DELETE FROM public.reglages_compte_unique WHERE id = 1");
        config.invalider();
    }

    @Test
    void enregistrement_reel_chiffre_le_secret_en_colonne() {
        String secret = "secret-de-test-schema-9876543210";
        config.enregistrer(new ConfigurationCompteUniqueImpl.ReglagesModifies("https://auth.exemple.org", "site-1",
                secret, "https://app.exemple.org", null, null), "test");

        String enBase = jdbc.queryForObject("SELECT client_secret FROM public.reglages_compte_unique WHERE id = 1",
                String.class);
        assertThat(enBase).isNotBlank().doesNotContain(secret);
        assertThat(contrat.clientSecret()).isEqualTo(secret);
        assertThat(contrat.version()).isPositive();
    }
}
