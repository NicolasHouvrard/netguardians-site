package com.logicielpourtous.site.features.identite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Clients particuliers : societe sans SIREN ni TVA, jamais avec l'e-mail d'un utilisateur existant. */
@SpringBootTest
@Transactional
class RegistreSocietesParticulierTest {

    @Autowired private RegistreSocietes registre;
    @Autowired private JdbcTemplate jdbc;

    private void creerUtilisateur(String email) {
        Long tenant = jdbc.queryForObject("SELECT id FROM tenants ORDER BY id LIMIT 1", Long.class);
        jdbc.update("INSERT INTO users (email, password, tenant_id) VALUES (?, 'x', ?)", email, tenant);
    }

    private long nbSocietes() {
        return jdbc.queryForObject("SELECT count(*) FROM societes_identites", Long.class);
    }

    @Test
    void enregistrerParticulier_creeUneSocieteSansNumerosAvecLaCapaciteClient() {
        SocieteIdentite s = registre.enregistrerParticulier("jean.dupont@example.test", 7L);

        assertThat(s.getId()).isNotNull();
        assertThat(s.isParticulier()).isTrue();
        assertThat(s.getSiren()).isNull();
        assertThat(s.getTva()).isNull();
        assertThat(s.getProprietaireTenantId()).isEqualTo(7L);
        assertThat(jdbc.queryForList("SELECT capacite FROM societe_capacites WHERE societe_id = ?",
                String.class, s.getId())).containsExactly("CLIENT");
    }

    @Test
    void enregistrerParticulier_refuseLEmailDUnUtilisateurExistantSansRienEcrire() {
        creerUtilisateur("deja.pris@example.test");
        long avant = nbSocietes();
        long capacitesAvant = jdbc.queryForObject("SELECT count(*) FROM societe_capacites", Long.class);

        for (String email : new String[] {"deja.pris@example.test", "  Deja.PRIS@Example.TEST "}) {
            assertThatThrownBy(() -> registre.enregistrerParticulier(email, null))
                    .isInstanceOf(EmailDejaUtiliseException.class);
        }

        assertThat(nbSocietes()).isEqualTo(avant);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM societe_capacites", Long.class))
                .isEqualTo(capacitesAvant);
    }

    @Test
    void laBaseRefuseUneSocieteNonParticuliereSansTva() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO societes_identites (siren, tva) VALUES ('123456782', NULL)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void laBaseRefuseUnParticulierAvecUnNumero() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO societes_identites (siren, tva, particulier) VALUES (NULL, 'FR123', TRUE)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void uneSocieteClassiqueSeComporteCommeAvant() {
        SocieteIdentite s = registre.enregistrer(TypeSociete.CLIENT,
                IdentiteSociete.depuis("404833048", null, "FR83404833048"), null);

        assertThat(s.isParticulier()).isFalse();
        assertThat(s.getTva()).isEqualTo("FR83404833048");
    }
}
