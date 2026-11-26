package com.logicielpourtous.site.features.licence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

/**
 * L'appel d'aiguillage (fait pendant la requete d'un utilisateur) a des delais courts ; les autres appels du client
 * de licence gardent leur comportement (pas de delai court). Serveur HTTP JDK jetable.
 */
class LicenceClientAiguillageDelaiTest {

    private static final long LENTEUR_MS = 5_000;

    private HttpServer serveur;
    private LicenceClient client;

    @BeforeEach
    void demarrer() throws IOException {
        serveur = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serveur.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }));
        // Accepte la connexion puis ne repond qu'apres LENTEUR_MS (plus que le delai d'aiguillage).
        serveur.createContext("/", ex -> {
            try {
                ex.getRequestBody().readAllBytes();
                Thread.sleep(LENTEUR_MS);
                byte[] corps = (ex.getRequestURI().getPath().endsWith("aiguillage") ? "{\"serveurs\":[]}" : "[]").getBytes();
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(200, corps.length);
                ex.getResponseBody().write(corps);
            } catch (InterruptedException | IOException e) {
                // client parti : rien a faire
            } finally {
                ex.close();
            }
        });
        serveur.start();
        EtatLicenceLocaleRepository repo = mock(EtatLicenceLocaleRepository.class);
        EtatLicenceLocale etat = new EtatLicenceLocale();
        etat.setCleApiLicence("cle-de-test");
        when(repo.findAll()).thenReturn(List.of(etat));
        client = new LicenceClient("http://127.0.0.1:" + serveur.getAddress().getPort(), repo, new ObjectMapper());
    }

    @AfterEach
    void arreter() {
        serveur.stop(0);
    }

    @Test
    void un_maitre_qui_ne_repond_pas_ne_bloque_pas_plus_de_quelques_secondes() {
        long debut = System.nanoTime();
        assertThatThrownBy(() -> client.aiguiller("jeton-fictif-pour-test")).isInstanceOf(RuntimeException.class);
        long ms = (System.nanoTime() - debut) / 1_000_000;
        assertThat(ms).isLessThan(LENTEUR_MS - 500);
    }

    @Test
    void les_autres_appels_du_client_de_licence_ne_changent_pas() {
        // Reponse apres 5 s : acceptee, car ces appels n'ont pas le delai court de l'aiguillage.
        assertThat(client.listerNouveauxServeurs()).isEmpty();
    }
}
