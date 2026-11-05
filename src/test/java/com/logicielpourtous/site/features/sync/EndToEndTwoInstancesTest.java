package com.logicielpourtous.site.features.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Validation de bout en bout de la synchronisation sur DEUX processus Spring Boot distincts (Task 5),
 * sans aucun mock : HTTP reel entre les deux instances, verification par SQL direct sur leurs DEUX bases.
 *
 * <p><b>Pourquoi {@code @Disabled} par defaut</b> : ce test ne demarre rien lui-meme. Il PILOTE deux
 * instances deja lancees a la main (deux JVM, deux ports, deux bases PostgreSQL), parce que
 * l'architecture a valider est justement "deux serveurs separes" : un seul contexte Spring de test ne
 * peut pas jouer a la fois le partenaire (sans {@code app.sync.master-url}, donc sans scheduler) et le
 * client (avec scheduler actif), ni avoir deux datasources distinctes pour la meme application. Il
 * depend en outre d'un enrolement prealable (ligne {@code secondary_servers} inseree en base,
 * {@code POST /api/sync/enroll}, cle API passee au client au demarrage) et d'un utilisateur ADMIN connu
 * dans chaque base. Rien de cela n'existe sur une machine de CI ni dans {@code mvn test}.
 *
 * <p><b>Pour l'executer</b> (procedure complete et preuve d'execution :
 * {@code .superpowers/sdd/2026-10-27-moteur-synchronisation/task-5-report.md}) :
 * <ol>
 *   <li>{@code java -jar target/site-0.0.1-SNAPSHOT.jar --spring.profiles.active=local,sync-partner} (port 8081, base {@code site_db}) ;</li>
 *   <li>enroler le client (voir rapport), puis
 *       {@code SYNC_API_KEY=<cle> java -jar target/site-0.0.1-SNAPSHOT.jar --spring.profiles.active=local,sync-client}
 *       (port 8082, base {@code site_db_secondaire}) ;</li>
 *   <li>retirer {@code @Disabled} localement et lancer
 *       {@code mvn test -Dtest=EndToEndTwoInstancesTest -De2e.user=... -De2e.password=... -De2e.db.password=...}.</li>
 * </ol>
 *
 * <p>Couvre les scenarios 2 (creation cote client -> visible cote partenaire, meme {@code syncId}) et 3
 * (changement de statut cote partenaire -> visible cote client). Le scenario 4 (conflit) exige de couper
 * puis retablir la connectivite du client, c'est-a-dire de REDEMARRER l'instance client avec une
 * {@code app.sync.master-url} injoignable puis correcte : il reste une procedure manuelle, decrite et
 * executee dans le rapport.
 */
@Disabled("Necessite 2 instances Spring Boot reelles lancees a la main (profils local,sync-partner et "
        + "local,sync-client) + enrolement prealable : voir la Javadoc de classe et task-5-report.md")
class EndToEndTwoInstancesTest {

    private static final String PARTNER_URL = System.getProperty("e2e.partner-url", "http://localhost:8081");
    private static final String CLIENT_URL = System.getProperty("e2e.client-url", "http://localhost:8082");
    private static final String PARTNER_JDBC = System.getProperty("e2e.partner-jdbc",
            "jdbc:postgresql://localhost:5432/site_db");
    private static final String CLIENT_JDBC = System.getProperty("e2e.client-jdbc",
            "jdbc:postgresql://localhost:5432/site_db_secondaire");
    private static final String DB_USER = System.getProperty("e2e.db.user", "nicolas");
    private static final String DB_PASSWORD = System.getProperty("e2e.db.password", "");
    private static final String APP_USER = System.getProperty("e2e.user", "sync-e2e@test.local");
    private static final String APP_PASSWORD = System.getProperty("e2e.password", "");

    /** Plusieurs cycles du scheduler client ({@code app.sync.poll-interval-ms=2000} dans sync-client). */
    private static final Duration DELAI_MAX = Duration.ofSeconds(30);

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void creationCoteClientPuisChangementCotePartenaire() throws Exception {
        // ── Scenario 2 : creation cote client -> visible cote partenaire, meme syncId ──
        String jetonClient = login(CLIENT_URL);
        String titre = "E2E auto " + UUID.randomUUID();
        JsonNode creee = call(CLIENT_URL, "POST", "/api/intra/tickets", jetonClient,
                Map.of("title", titre, "description", "EndToEndTwoInstancesTest", "priority", "HIGH",
                        "type", "SUPPORT"));
        long idClient = creee.get("id").asLong();
        UUID syncId = UUID.fromString(scalar(CLIENT_JDBC, "select sync_id from requests where id = ?", idClient));

        String idPartenaire = attendre(() -> scalarOrNull(PARTNER_JDBC,
                "select id from requests where sync_id = ?", syncId));
        assertThat(scalar(PARTNER_JDBC, "select title from requests where sync_id = ?", syncId)).isEqualTo(titre);
        assertThat(scalar(PARTNER_JDBC, "select customer_id from requests where sync_id = ?", syncId))
                .as("rattachee au client du serveur secondaire enrole").isNotNull();

        // ── Scenario 3 : changement de statut cote partenaire -> visible cote client ──
        String jetonPartenaire = login(PARTNER_URL);
        call(PARTNER_URL, "PATCH", "/api/intra/tickets/" + idPartenaire + "/status", jetonPartenaire,
                Map.of("status", "IN_PROGRESS"));

        String statutClient = attendre(() -> {
            String s = scalarOrNull(CLIENT_JDBC, "select status from requests where sync_id = ?", syncId);
            return "IN_PROGRESS".equals(s) ? s : null;
        });
        assertThat(statutClient).isEqualTo("IN_PROGRESS");
    }

    // ── Outils ───────────────────────────────────────────────────────────────

    private String login(String baseUrl) throws Exception {
        return call(baseUrl, "POST", "/api/auth/login", null,
                Map.of("email", APP_USER, "password", APP_PASSWORD)).get("token").asText();
    }

    /**
     * Le corps de la reponse peut etre absent ou en erreur (ex. 500 a la serialisation de la reponse
     * alors que l'ecriture est deja validee) : l'appelant verifie de toute facon l'effet par SQL.
     */
    private JsonNode call(String baseUrl, String method, String path, String jeton, Object corps) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(corps)));
        if (jeton != null) {
            req.header("Authorization", "Bearer " + jeton);
        }
        HttpResponse<String> rep = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        return rep.body() == null || rep.body().isBlank() ? json.createObjectNode() : json.readTree(rep.body());
    }

    private static String attendre(Supplier<String> sonde) throws InterruptedException {
        Instant limite = Instant.now().plus(DELAI_MAX);
        while (Instant.now().isBefore(limite)) {
            String valeur = sonde.get();
            if (valeur != null) {
                return valeur;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("Synchronisation non observee apres " + DELAI_MAX);
    }

    private static String scalar(String jdbc, String sql, Object param) {
        String valeur = scalarOrNull(jdbc, sql, param);
        assertThat(valeur).as(sql).isNotNull();
        return valeur;
    }

    private static String scalarOrNull(String jdbc, String sql, Object param) {
        try (Connection c = DriverManager.getConnection(jdbc, DB_USER, DB_PASSWORD);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
