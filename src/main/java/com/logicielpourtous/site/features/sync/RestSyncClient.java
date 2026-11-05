package com.logicielpourtous.site.features.sync;

import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Implementation REST ({@link RestClient}) de {@link SyncClient}. Active uniquement sur un
 * secondaire ({@code app.sync.master-url} configure).
 *
 * <p>Configuration (fichier de proprietes non suivi par git, a l'image d'{@code application-local.properties}) :
 * {@code app.sync.master-url} (URL du maitre/partenaire) et {@code app.sync.api-key} (cle recue a
 * l'enrolement, envoyee dans l'en-tete {@value SyncApiKeyAuthFilter#HEADER}).
 *
 * <p>La (de)serialisation JSON est faite explicitement avec l'{@link ObjectMapper} de l'application
 * (corps en {@code String}) plutot que par les convertisseurs implicites de {@code RestClient} : le
 * format echange (dates ISO, champs inconnus ignores) ne depend ainsi pas de la bibliotheque Jackson
 * que {@code RestClient} choisirait.
 */
@Component
@ConditionalOnProperty(name = "app.sync.master-url")
public class RestSyncClient implements SyncClient {

    private static final String EVENTS_PATH = "/api/sync/events";

    private final RestClient restClient;
    private final String apiKey;
    private final ObjectMapper objectMapper;

    public RestSyncClient(@Value("${app.sync.master-url}") String masterUrl,
                          @Value("${app.sync.api-key:}") String apiKey,
                          ObjectMapper objectMapper) {
        this.restClient = RestClient.builder().baseUrl(masterUrl).build();
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<SyncReceptionService.ResultatReception> push(List<RequestSyncPayload> events) {
        String response = restClient.post()
                .uri(EVENTS_PATH)
                .header(SyncApiKeyAuthFilter.HEADER, requireApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(write(events))
                .retrieve()
                .body(String.class);
        return read(response, new TypeReference<SyncEventsController.PushResponse>() {}).resultats();
    }

    @Override
    public PullResult pull(Instant since) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromPath(EVENTS_PATH);
        if (since != null) {
            uri.queryParam("since", since.toString());
        }
        ResponseEntity<String> response = restClient.get()
                .uri(uri.build().encode().toUriString())
                .header(SyncApiKeyAuthFilter.HEADER, requireApiKey())
                .retrieve()
                .toEntity(String.class);
        List<RequestSyncPayload> events = read(response.getBody(), new TypeReference<List<RequestSyncPayload>>() {});
        String cursor = response.getHeaders().getFirst(SyncEventsController.CURSOR_HEADER);
        return new PullResult(events, cursor == null || cursor.isBlank() ? null : Instant.parse(cursor));
    }

    private String requireApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("app.sync.api-key non configuree : secondaire non enrole");
        }
        return apiKey;
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Serialisation JSON impossible", e);
        }
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json == null ? "null" : json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Reponse JSON du maitre illisible", e);
        }
    }
}
