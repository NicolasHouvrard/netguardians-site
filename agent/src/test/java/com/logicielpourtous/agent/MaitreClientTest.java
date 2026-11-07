package com.logicielpourtous.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;

import com.sun.net.httpserver.HttpServer;

/**
 * {@link MaitreClient} contre un VRAI serveur HTTP local (jouant le Maitre, {@code
 * com.sun.net.httpserver.HttpServer} du JDK — aucune dependance de mock ajoutee) : prouve que le
 * format echange (en-tete de cle API, lecture du corps JSON) est bien compris, et que la lecture
 * de la cle API licence LOCALE (fichier, voir {@code AgentCleApiFileWriter} de l'appli
 * principale) se comporte correctement dans les 3 cas prevus par le plan : fichier absent,
 * fichier vide, fichier contenant une cle.
 *
 * <p>PAS de serveur de test pour "l'appli locale" (contrairement a la version du brief, obsolete
 * — voir task-5-brief.md en tete de fichier de ce chantier) : la cle API licence locale est lue
 * directement dans un fichier, plus d'appel HTTP interne necessaire.
 */
class MaitreClientTest {

    @TempDir
    Path tmpDir;

    private HttpServer maitre;

    @AfterEach
    void arreterMaitre() {
        if (maitre != null) {
            maitre.stop(0);
        }
    }

    @Test
    void recupererOrdre_fichierCleApiAbsent_renvoieVideSansAppelerLeMaitre() throws IOException {
        Path cheminCle = tmpDir.resolve("cle-api-licence-inexistante");
        List<String> appelsRecus = new CopyOnWriteArrayList<>();
        demarrerMaitre("{\"version\":\"v1.2.3\"}", appelsRecus);

        MaitreClient client = new MaitreClient(cheminCle, "http://localhost:" + maitre.getAddress().getPort());

        assertThat(client.recupererOrdre()).isEmpty();
        assertThat(appelsRecus).isEmpty();
    }

    @Test
    void recupererOrdre_fichierCleApiVide_renvoieVideSansAppelerLeMaitre() throws IOException {
        Path cheminCle = tmpDir.resolve("cle-api-licence-vide");
        Files.writeString(cheminCle, "   \n");
        List<String> appelsRecus = new CopyOnWriteArrayList<>();
        demarrerMaitre("{\"version\":\"v1.2.3\"}", appelsRecus);

        MaitreClient client = new MaitreClient(cheminCle, "http://localhost:" + maitre.getAddress().getPort());

        assertThat(client.recupererOrdre()).isEmpty();
        assertThat(appelsRecus).isEmpty();
    }

    @Test
    void recupererOrdreUtiliseLaCleLueLocalement() throws IOException {
        Path cheminCle = tmpDir.resolve("cle-api-licence");
        Files.writeString(cheminCle, "abc\n");
        List<String> appelsRecus = new CopyOnWriteArrayList<>();
        demarrerMaitre("{\"version\":\"v1.2.3\"}", appelsRecus);

        MaitreClient client = new MaitreClient(cheminCle, "http://localhost:" + maitre.getAddress().getPort());

        assertThat(client.recupererOrdre()).contains("v1.2.3");
        assertThat(appelsRecus).containsExactly("abc");
    }

    @Test
    void recupererOrdre_cleAvecCaracteresInterdits_estIgnoreeSansAppelerLeMaitre() throws IOException {
        // Un saut de ligne dans la cle serait une injection d'en-tete HTTP (le fichier est ecrit par
        // l'application, pas de confiance).
        Path cheminCle = tmpDir.resolve("cle-api-licence-piegee");
        Files.writeString(cheminCle, "abc\r\nX-Evil: 1");
        List<String> appelsRecus = new CopyOnWriteArrayList<>();
        demarrerMaitre("{\"version\":\"v1.2.3\"}", appelsRecus);

        MaitreClient client = new MaitreClient(cheminCle, "http://localhost:" + maitre.getAddress().getPort());

        assertThat(client.recupererOrdre()).isEmpty();
        assertThat(appelsRecus).isEmpty();
    }

    @Test
    void recupererOrdre_fichierTropGros_estIgnoreSansAppelerLeMaitre() throws IOException {
        Path cheminCle = tmpDir.resolve("cle-api-licence-enorme");
        Files.writeString(cheminCle, "a".repeat(10_000));
        List<String> appelsRecus = new CopyOnWriteArrayList<>();
        demarrerMaitre("{\"version\":\"v1.2.3\"}", appelsRecus);

        MaitreClient client = new MaitreClient(cheminCle, "http://localhost:" + maitre.getAddress().getPort());

        assertThat(client.recupererOrdre()).isEmpty();
        assertThat(appelsRecus).isEmpty();
    }

    @Test
    void recupererOrdre_lienSymboliqueVersUnAutreFichier_estIgnore() throws IOException {
        Path vraie = tmpDir.resolve("autre-fichier-sensible");
        Files.writeString(vraie, "abc");
        Path lien = tmpDir.resolve("cle-api-licence-lien");
        try {
            Files.createSymbolicLink(lien, vraie);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            // Liens symboliques indisponibles (ex. Windows sans droit) : cas non testable ici.
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "liens symboliques non disponibles");
        }
        List<String> appelsRecus = new CopyOnWriteArrayList<>();
        demarrerMaitre("{\"version\":\"v1.2.3\"}", appelsRecus);

        MaitreClient client = new MaitreClient(lien, "http://localhost:" + maitre.getAddress().getPort());

        assertThat(client.recupererOrdre()).isEmpty();
        assertThat(appelsRecus).isEmpty();
    }

    @Test
    void recupererOrdre_versionNulleCoteMaitre_renvoieVide() throws IOException {
        Path cheminCle = tmpDir.resolve("cle-api-licence");
        Files.writeString(cheminCle, "abc");
        List<String> appelsRecus = new CopyOnWriteArrayList<>();
        demarrerMaitre("{\"version\":null}", appelsRecus);

        MaitreClient client = new MaitreClient(cheminCle, "http://localhost:" + maitre.getAddress().getPort());

        assertThat(client.recupererOrdre()).isEqualTo(Optional.empty());
    }

    // Securite scan, fix round 1 (finding 2) : pas de test de bout en bout (lent/fragile —
    // necessiterait un vrai socket qui ne repond jamais) ; verification unitaire directe de la
    // configuration de la factory dediee, meme patron que
    // DeploiementSchedulerTest#construireRequestFactoryAvecTimeout_configuresShortConnectAndReadTimeouts
    // cote appli principale (reflexion sur les champs prives, aucun getter public n'existe sur
    // JdkClientHttpRequestFactory).
    //
    // Securite scan, fix round 3 : connexion et lecture ne partagent PLUS la meme valeur — voir
    // la javadoc de MaitreClient.TIMEOUT_LECTURE sur le risque de "lost update" (consume-on-read
    // cote Maitre) qui justifie un delai de lecture plus genereux (10s) que le delai de connexion
    // (reste a 3s, un Maitre injoignable doit toujours echouer vite).
    @Test
    void construireRequestFactoryAvecTimeout_configuresShortConnectButGenerousReadTimeout() {
        JdkClientHttpRequestFactory factory = MaitreClient.construireRequestFactoryAvecTimeout();

        HttpClient httpClient = (HttpClient) ReflectionTestUtils.getField(factory, "httpClient");
        Duration readTimeout = (Duration) ReflectionTestUtils.getField(factory, "readTimeout");

        assertThat(httpClient).isNotNull();
        assertThat(httpClient.connectTimeout()).contains(Duration.ofSeconds(3));
        assertThat(readTimeout).isEqualTo(Duration.ofSeconds(10));
    }

    /**
     * Demarre un serveur HTTP local jouant le Maitre : enregistre dans {@code appelsRecus} la
     * valeur de l'en-tete {@code X-Licence-Api-Key} de chaque requete recue sur {@code
     * /api/licence/ordre-deploiement}, puis repond {@code corpsReponse} (JSON) avec 200 OK.
     */
    private void demarrerMaitre(String corpsReponse, List<String> appelsRecus) throws IOException {
        maitre = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        maitre.createContext("/api/licence/ordre-deploiement", exchange -> {
            String cleRecue = exchange.getRequestHeaders().getFirst("X-Licence-Api-Key");
            appelsRecus.add(cleRecue);
            byte[] corps = corpsReponse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, corps.length);
            exchange.getResponseBody().write(corps);
            exchange.close();
        });
        maitre.start();
    }
}
