package com.logicielpourtous.site.features.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicielpourtous.site.features.identite.NumerosSociete;
import com.logicielpourtous.site.sdk.GestionPartenairesContrat.CreationPartenaire;
import com.logicielpourtous.site.sdk.GestionPartenairesContrat.ResultatPartenaire;

/** Creation complete d'un partenaire. Sans transaction de test : les ecritures sont reelles, nettoyees ensuite. */
@SpringBootTest
class CreationPartenaireServiceTest {

    private static final Set<String> INITIAL = Set.of("clients.lire", "clients.creer", "clients.entrer");

    @Autowired private CreationPartenaireService service;
    @Autowired private ModeleDroitsPartenaireService modele;
    @Autowired private DroitsService droits;
    @Autowired private JdbcTemplate jdbc;

    private final List<String> sirens = new ArrayList<>();
    private final List<String> emails = new ArrayList<>();

    @AfterEach
    void nettoyer() {
        modele.remplacer(INITIAL);
        for (String email : emails) {
            jdbc.update("DELETE FROM appartenances_roles WHERE appartenance_id IN "
                    + "(SELECT a.id FROM appartenances a JOIN users u ON u.id = a.user_id WHERE u.email = ?)", email);
            jdbc.update("DELETE FROM appartenances WHERE user_id IN (SELECT id FROM users WHERE email = ?)", email);
            jdbc.update("DELETE FROM users_roles WHERE user_id IN (SELECT id FROM users WHERE email = ?)", email);
            jdbc.update("DELETE FROM users WHERE email = ?", email);
        }
        for (String siren : sirens) {
            for (Long id : jdbc.queryForList("SELECT id FROM societes_identites WHERE siren = ?", Long.class, siren)) {
                Long tenant = jdbc.queryForObject("SELECT proprietaire_tenant_id FROM societes_identites WHERE id = ?",
                        Long.class, id);
                jdbc.update("DELETE FROM reglages_manager WHERE client_societe_id = ?", id);
                jdbc.update("DELETE FROM plafonds_client WHERE client_societe_id = ?", id);
                jdbc.update("DELETE FROM partenaires_etat WHERE societe_id = ?", id);
                jdbc.update("DELETE FROM societe_capacites WHERE societe_id = ?", id);
                jdbc.update("DELETE FROM appartenances WHERE societe_id = ?", id);
                jdbc.update("DELETE FROM societes_identites WHERE id = ?", id);
                if (tenant != null) {
                    String schema = jdbc.queryForObject("SELECT schema_name FROM tenants WHERE id = ?", String.class,
                            tenant);
                    jdbc.update("DELETE FROM tenants WHERE id = ?", tenant);
                    jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
                }
            }
        }
    }

    private String siren() {
        for (int n = 700000000 + (int) (Math.random() * 90000000);; n++) {
            String s = String.valueOf(n);
            if (NumerosSociete.sirenValide(s)
                    && jdbc.queryForObject("SELECT count(*) FROM societes_identites WHERE siren = ?", Long.class, s) == 0) {
                sirens.add(s);
                return s;
            }
        }
    }

    private String email() {
        String e = "partenaire-" + UUID.randomUUID() + "@example.test";
        emails.add(e);
        return e;
    }

    private long compter(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private long schemas() {
        return jdbc.queryForObject("SELECT count(*) FROM information_schema.schemata WHERE schema_name LIKE 'p\\_%'",
                Long.class);
    }

    private CreationPartenaire demande(String siren, String email) {
        return new CreationPartenaire(siren, null, "Maison Test SARL", email, "Alice Martin");
    }

    @Test
    void creerUnPartenaireEcritToutEnUneFois() {
        String siren = siren();
        String email = email();

        ResultatPartenaire r = service.creer(demande(siren, email));

        assertThat(r.issue()).isEqualTo("CREE");
        Long id = r.societeId();
        assertThat(id).isNotNull();
        assertThat(jdbc.queryForObject("SELECT type FROM societes_identites WHERE id = ?", String.class, id))
                .isEqualTo("PARTENAIRE");
        Long tenant = jdbc.queryForObject("SELECT proprietaire_tenant_id FROM societes_identites WHERE id = ?",
                Long.class, id);
        assertThat(tenant).isNotNull();
        String schema = jdbc.queryForObject("SELECT schema_name FROM tenants WHERE id = ?", String.class, tenant);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.schemata WHERE schema_name = ?",
                Long.class, schema)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM societe_capacites WHERE societe_id = ? AND capacite = 'PARTENAIRE'", Long.class,
                id)).isEqualTo(1L);
        Long userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM users WHERE id = ?", Long.class, userId)).isEqualTo(tenant);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM appartenances a JOIN appartenances_roles ar ON ar.appartenance_id = a.id "
                        + "JOIN roles r ON r.id = ar.role_id WHERE a.user_id = ? AND a.societe_id = ? "
                        + "AND r.name = 'ROLE_GESTIONNAIRE'", Long.class, userId, id)).isEqualTo(1L);
        assertThat(droits.autorise(userId, id, "clients.creer")).isTrue();
        assertThat(jdbc.queryForObject("SELECT etat FROM partenaires_etat WHERE societe_id = ?", String.class, id))
                .isEqualTo("ACTIF");
        Instant debut = jdbc.queryForObject("SELECT debut_validation FROM partenaires_etat WHERE societe_id = ?",
                java.sql.Timestamp.class, id).toInstant();
        assertThat(Duration.between(debut, Instant.now()).abs()).isLessThan(Duration.ofMinutes(2));
        assertThat(jdbc.queryForList("SELECT droit FROM plafonds_client WHERE client_societe_id = ?", String.class, id))
                .containsExactlyInAnyOrderElementsOf(INITIAL);
        assertThat(jdbc.queryForList("SELECT droit FROM reglages_manager WHERE client_societe_id = ?", String.class, id))
                .containsExactlyInAnyOrderElementsOf(INITIAL);
    }

    @Test
    void sirenDejaConnuNEcritRien() {
        String siren = siren();
        assertThat(service.creer(demande(siren, email())).issue()).isEqualTo("CREE");
        long tenants = compter("tenants");
        long identites = compter("societes_identites");
        long users = compter("users");
        long demandes = compter("demandes_societe_connue");
        long schemas = schemas();

        ResultatPartenaire r = service.creer(demande(siren, email()));

        assertThat(r.issue()).isEqualTo("SIREN_DEJA_CONNU");
        assertThat(r.societeId()).isNull();
        assertThat(compter("tenants")).isEqualTo(tenants);
        assertThat(compter("societes_identites")).isEqualTo(identites);
        assertThat(compter("users")).isEqualTo(users);
        assertThat(compter("demandes_societe_connue")).isEqualTo(demandes);
        assertThat(schemas()).isEqualTo(schemas);
    }

    @Test
    void emailDejaUtiliseNEcritRien() {
        String email = email();
        assertThat(service.creer(demande(siren(), email)).issue()).isEqualTo("CREE");
        long tenants = compter("tenants");
        long identites = compter("societes_identites");
        long users = compter("users");
        long demandes = compter("demandes_societe_connue");
        long schemas = schemas();

        ResultatPartenaire r = service.creer(demande(siren(), email));

        assertThat(r.issue()).isEqualTo("EMAIL_DEJA_UTILISE");
        assertThat(r.societeId()).isNull();
        assertThat(compter("tenants")).isEqualTo(tenants);
        assertThat(compter("societes_identites")).isEqualTo(identites);
        assertThat(compter("users")).isEqualTo(users);
        assertThat(compter("demandes_societe_connue")).isEqualTo(demandes);
        assertThat(schemas()).isEqualTo(schemas);
    }

    @Test
    void modifierLeModeleApresCreationNeChangePasLesDroitsDuPartenaire() {
        String email = email();
        ResultatPartenaire r = service.creer(demande(siren(), email));
        Long userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);

        modele.remplacer(Set.of("clients.lire"));

        assertThat(droits.autorise(userId, r.societeId(), "clients.creer")).isTrue();
    }

    @Test
    void entreesInvalidesLevent() {
        long tenants = compter("tenants");
        long identites = compter("societes_identites");
        long users = compter("users");
        long schemas = schemas();
        String bon = siren();
        assertThatThrownBy(() -> service.creer(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.creer(demande("123456789", "a@example.test")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.creer(new CreationPartenaire(bon, "DE123456789", "X", "a@example.test", "N")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.creer(new CreationPartenaire(bon, null, "X", "a@example.test", " ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.creer(new CreationPartenaire(bon, null, "X", "pas-un-email", "N")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.creer(new CreationPartenaire(bon, null, " ", "a@example.test", "N")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(compter("tenants")).isEqualTo(tenants);
        assertThat(compter("societes_identites")).isEqualTo(identites);
        assertThat(compter("users")).isEqualTo(users);
        assertThat(schemas()).isEqualTo(schemas);
    }
}
