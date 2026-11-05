package com.logicielpourtous.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * {@link ControleSante} contre de VRAIS serveurs HTTP locaux ({@code com.sun.net.httpserver} du
 * JDK, aucun mock) : prouve le polling reel de {@code /actuator/health}.
 */
class ControleSanteTest {

    private HttpServer serveur;

    @AfterEach
    void arreterServeur() {
        if (serveur != null) {
            serveur.stop(0);
        }
    }

    @Test
    void renvoieFauxSiJamaisSainDansLaFenetre() throws IOException {
        // Port reserve puis libere : aucune ecoute dessus, chaque tentative echoue en connexion refusee.
        int portServeurQuiNeRepondJamais;
        try (ServerSocket libre = new ServerSocket(0)) {
            portServeurQuiNeRepondJamais = libre.getLocalPort();
        }

        var controle = new ControleSante();
        boolean sain = controle.attendreSante(portServeurQuiNeRepondJamais, Duration.ofSeconds(2));
        assertThat(sain).isFalse();
    }

    @Test
    void renvoieVraiDesQueLeServeurRepondUp() throws IOException {
        // serveur de test repondant 200 {"status":"UP"} sur /actuator/health
        int portServeurSain = demarrerServeurHealth(200, "{\"status\":\"UP\"}");

        var controle = new ControleSante();
        assertThat(controle.attendreSante(portServeurSain, Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void renvoieFauxSiLeServeurRepondDownDansLaFenetre() throws IOException {
        int portServeurDown = demarrerServeurHealth(503, "{\"status\":\"DOWN\"}");

        var controle = new ControleSante();
        assertThat(controle.attendreSante(portServeurDown, Duration.ofSeconds(2))).isFalse();
    }

    private int demarrerServeurHealth(int code, String corps) throws IOException {
        serveur = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        serveur.createContext("/actuator/health", echange -> {
            byte[] octets = corps.getBytes(StandardCharsets.UTF_8);
            echange.getResponseHeaders().add("Content-Type", "application/json");
            echange.sendResponseHeaders(code, octets.length);
            try (OutputStream sortie = echange.getResponseBody()) {
                sortie.write(octets);
            }
        });
        serveur.start();
        return serveur.getAddress().getPort();
    }
}
