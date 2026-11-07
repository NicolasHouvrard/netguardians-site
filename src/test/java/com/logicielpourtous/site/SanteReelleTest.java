package com.logicielpourtous.site;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reproduction sur un VRAI serveur (Tomcat, port reel) avec un element de menu public comme sur le
 * Maitre ("essai" -> /test) : GET /actuator/health, GET /, et une page inexistante.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SanteReelleTest {

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void menu() {
        jdbc.update("DELETE FROM menu_items WHERE label = 'essai-test'");
        jdbc.update("INSERT INTO menu_items (active, label, \"position\", sort_order, target, url, menu_position) "
                + "VALUES (true, 'essai-test', 'MAIN', 1, '_self', '/test', 'MAIN')");
    }

    @AfterEach
    void nettoyer() {
        jdbc.update("DELETE FROM menu_items WHERE label = 'essai-test'");
    }

    private HttpResponse<String> get(String chemin) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + chemin)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void actuatorHealth_repond200Up() throws Exception {
        HttpResponse<String> r = get("/actuator/health");
        assertThat(r.statusCode()).as("health: " + r.body().substring(0, Math.min(200, r.body().length()))).isEqualTo(200);
        assertThat(r.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void accueil_repondSansErreurServeur() throws Exception {
        HttpResponse<String> r = get("/");
        assertThat(r.statusCode()).as("accueil").isLessThan(500);
    }

    @Test
    void pageInexistante_afficheUne404PasUne500() throws Exception {
        HttpResponse<String> r = get("/page-qui-nexiste-pas-xyz");
        assertThat(r.statusCode()).as("404").isLessThan(500);
    }
}
