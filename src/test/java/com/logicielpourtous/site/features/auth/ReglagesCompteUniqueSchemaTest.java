package com.logicielpourtous.site.features.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicielpourtous.site.features.auth.ConfigurationCompteUniqueImpl.ReglagesModifies;
import com.logicielpourtous.site.features.commun.ConfigurationCompteUnique;

/** Schema V20 (ddl-auto=validate) et ecritures reelles : secret chiffre en colonne, creation et version sous concurrence. */
@SpringBootTest
class ReglagesCompteUniqueSchemaTest {

    @Autowired
    private ConfigurationCompteUniqueImpl config;

    @Autowired
    private ConfigurationCompteUnique contrat;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void nettoyer() {
        jdbc.update("DELETE FROM public.reglages_compte_unique WHERE id = 1");
        config.invalider();
    }

    @Test
    void la_colonne_ne_contient_jamais_le_secret_en_clair() {
        String secret = "secret-de-test-schema-9876543210";
        config.enregistrer(new ReglagesModifies("https://auth.exemple.org", "site-1", secret,
                "https://app.exemple.org", null, null), "test");

        String enBase = jdbc.queryForObject("SELECT client_secret FROM public.reglages_compte_unique WHERE id = 1",
                String.class);
        assertThat(enBase).isNotBlank().doesNotContain(secret);
        assertThat(contrat.clientSecret()).isEqualTo(secret);
        assertThat(contrat.version()).isPositive();

        config.remplacerSecret("autre-secret-de-test-1122334455", "test");
        String apres = jdbc.queryForObject("SELECT client_secret FROM public.reglages_compte_unique WHERE id = 1",
                String.class);
        assertThat(apres).isNotEqualTo(enBase).doesNotContain("autre-secret-de-test-1122334455");
        assertThat(contrat.clientSecret()).isEqualTo("autre-secret-de-test-1122334455");
    }

    @Test
    void deux_premiers_enregistrements_simultanes_ne_dupliquent_pas_la_ligne() throws Exception {
        int n = 2;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CyclicBarrier depart = new CyclicBarrier(n);
            List<Future<?>> taches = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String id = "site-" + i;
                taches.add(pool.submit(() -> {
                    depart.await(10, TimeUnit.SECONDS);
                    config.enregistrer(new ReglagesModifies("https://auth.exemple.org", id, "secret-de-test-" + id
                            + "-0123456789", null, null, null), "test");
                    return null;
                }));
            }
            for (Future<?> f : taches) {
                f.get(30, TimeUnit.SECONDS); // propage toute exception (cle dupliquee comprise)
            }
        } finally {
            pool.shutdownNow();
        }

        Integer lignes = jdbc.queryForObject("SELECT count(*) FROM public.reglages_compte_unique", Integer.class);
        Long version = jdbc.queryForObject("SELECT version FROM public.reglages_compte_unique WHERE id = 1",
                Long.class);
        assertThat(lignes).isEqualTo(1);
        assertThat(version).isEqualTo(2L); // jamais deux enregistrements avec la meme version
    }
}
