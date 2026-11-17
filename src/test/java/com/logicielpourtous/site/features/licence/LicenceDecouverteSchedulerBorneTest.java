package com.logicielpourtous.site.features.licence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.logicielpourtous.site.features.commun.ReessaiRetryAfter;
import com.logicielpourtous.site.features.commun.ServerRole;
import com.logicielpourtous.site.features.commun.ServerRoleProperties;
import com.logicielpourtous.site.features.sync.SecondaryServer;
import com.logicielpourtous.site.features.sync.SecondaryServerRepository;
import com.sun.net.httpserver.HttpServer;

/**
 * Un Maitre en maintenance permanente (503 + Retry-After sur /code-partenaire) avec N entrees en
 * attente : l'attente de Retry-After ne doit etre payee qu'une fois par cycle, pas N fois.
 */
class LicenceDecouverteSchedulerBorneTest {

    private HttpServer serveur;
    private final AtomicInteger postsRecus = new AtomicInteger();
    private final List<Duration> attentes = new ArrayList<>();

    @BeforeEach
    void demarrer() throws IOException {
        serveur = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serveur.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            if ("GET".equals(ex.getRequestMethod())) {
                byte[] corps = ("[{\"identifiantInstallationStable\":\"i1\",\"customerId\":1,\"statut\":\"ACTIF\"},"
                        + "{\"identifiantInstallationStable\":\"i2\",\"customerId\":2,\"statut\":\"ACTIF\"},"
                        + "{\"identifiantInstallationStable\":\"i3\",\"customerId\":3,\"statut\":\"ACTIF\"},"
                        + "{\"identifiantInstallationStable\":\"i4\",\"customerId\":4,\"statut\":\"ACTIF\"}]").getBytes();
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(200, corps.length);
                ex.getResponseBody().write(corps);
            } else {
                postsRecus.incrementAndGet();
                ex.getResponseHeaders().set("Retry-After", "10");
                ex.sendResponseHeaders(503, -1);
            }
            ex.close();
        });
        serveur.start();
    }

    @AfterEach
    void arreter() {
        serveur.stop(0);
    }

    @Test
    void maitre_en_maintenance_permanente_attente_bornee_par_cycle() {
        EtatLicenceLocaleRepository etatRepo = mock(EtatLicenceLocaleRepository.class);
        EtatLicenceLocale etat = new EtatLicenceLocale();
        etat.setCleApiLicence("cle-de-test");
        when(etatRepo.findAll()).thenReturn(List.of(etat));
        LicenceClient client = new LicenceClient("http://127.0.0.1:" + serveur.getAddress().getPort(), etatRepo,
                new ObjectMapper(), new ReessaiRetryAfter(attentes::add));
        SecondaryServerRepository repo = mock(SecondaryServerRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        when(repo.save(any(SecondaryServer.class))).thenAnswer(i -> i.getArgument(0));
        ServerRoleProperties role = mock(ServerRoleProperties.class);
        when(role.getRole()).thenReturn(ServerRole.PARTNER);

        new LicenceDecouverteScheduler(client, repo, role).decouvrir();

        // 1 appel + 2 reessais sur la 1re entree seulement (2 attentes de 10 s), puis cycle abandonne.
        assertThat(postsRecus.get()).isEqualTo(3);
        assertThat(attentes).hasSize(2);
    }
}
