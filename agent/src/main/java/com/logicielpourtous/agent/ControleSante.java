package com.logicielpourtous.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Controle de sante de l'Agent (Task 7) : interroge {@code /actuator/health} de l'appli lancee
 * sur un port donne (Task 1) jusqu'a obtenir {@code 200 {"status":"UP"}}, ou jusqu'a expiration
 * d'une fenetre d'attente.
 *
 * <p>Une exception reseau (connexion refusee pendant que le conteneur demarre, delai depasse,
 * reponse non 2xx) est une tentative ratee, jamais une erreur fatale.
 */
@Component
public class ControleSante {

    private static final String CHEMIN_HEALTH = "/actuator/health";
    private static final Duration PAUSE_ENTRE_TENTATIVES = Duration.ofSeconds(1);
    private static final Duration TIMEOUT_CONNEXION = Duration.ofSeconds(1);
    private static final Duration TIMEOUT_LECTURE = Duration.ofSeconds(2);
    private static final Pattern STATUS_UP = Pattern.compile("\"status\"\\s*:\\s*\"UP\"");

    /**
     * Attend que l'appli sur {@code localhost:<port>} soit saine.
     *
     * @return {@code true} des qu'une reponse {@code 200} contient {@code "status":"UP"} ;
     *         {@code false} si la fenetre est depassee (ou si le thread est interrompu)
     */
    public boolean attendreSante(int port, Duration fenetre) {
        RestClient client = creerClient(port);
        Instant echeance = Instant.now().plus(fenetre);
        while (Instant.now().isBefore(echeance)) {
            if (estSain(client)) {
                return true;
            }
            try {
                Thread.sleep(PAUSE_ENTRE_TENTATIVES.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static boolean estSain(RestClient client) {
        try {
            var reponse = client.get().uri(CHEMIN_HEALTH).retrieve().toEntity(String.class);
            return reponse.getStatusCode().value() == 200
                    && reponse.getBody() != null
                    && STATUS_UP.matcher(reponse.getBody()).find();
        } catch (RestClientException e) {
            return false;
        }
    }

    private static RestClient creerClient(int port) {
        var httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(TIMEOUT_CONNEXION)
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(TIMEOUT_LECTURE);
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .requestFactory(requestFactory)
                .build();
    }
}
