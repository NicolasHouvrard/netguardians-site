package com.logicielpourtous.site.features.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.logicielpourtous.site.features.identite.RegistreSocietes;

import com.logicielpourtous.site.features.identite.NumerosSociete;
import com.logicielpourtous.site.sdk.GestionPartenairesContrat.CreationPartenaire;

/** Atomicite de la creation d'un partenaire, SANS transaction de test (rien ne doit subsister, pas de nettoyage). */
@SpringBootTest
class CreationPartenaireTransactionTest {

    @Autowired private CreationPartenaireService service;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private RegistreSocietes registre;
    @MockitoSpyBean private PremierManagerService premierManager;

    private long compter(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private long schemas() {
        return jdbc.queryForObject("SELECT count(*) FROM information_schema.schemata WHERE schema_name LIKE 'p\\_%'",
                Long.class);
    }

    @Test
    void unEchecSurLUtilisateurAnnuleTenantSchemaSocieteEtCapacite() {
        String siren = null;
        for (int n = 710000000; siren == null; n++) {
            if (NumerosSociete.sirenValide(String.valueOf(n)) && jdbc.queryForObject(
                    "SELECT count(*) FROM societes_identites WHERE siren = ?", Long.class, String.valueOf(n)) == 0) {
                siren = String.valueOf(n);
            }
        }
        long tenants = compter("tenants");
        long societes = compter("societes_identites");
        long capacites = compter("societe_capacites");
        long schemas = schemas();
        // L'e-mail est plafonne a 255 caracteres par le service : l'echec de l'ecriture de l'utilisateur est donc simule
        // (violation d'integrite) APRES la creation reelle du tenant, du schema, de la societe et de la capacite.
        doThrow(new DataIntegrityViolationException("simule")).when(premierManager).creer(any(), any(), any());
        String sirenFinal = siren;

        assertThatThrownBy(() -> service.creer(
                new CreationPartenaire(sirenFinal, null, "Maison Echec SARL", "zoe@example.test", "Zoe Test")))
                .isInstanceOf(DataIntegrityViolationException.class);
        // La societe a bien ete tentee avant l'echec de l'insertion de l'utilisateur.
        verify(registre, times(1)).enregistrer(any(), any(), any());

        assertThat(compter("tenants")).isEqualTo(tenants);
        assertThat(compter("societes_identites")).isEqualTo(societes);
        assertThat(compter("societe_capacites")).isEqualTo(capacites);
        assertThat(schemas()).isEqualTo(schemas);
    }
}
