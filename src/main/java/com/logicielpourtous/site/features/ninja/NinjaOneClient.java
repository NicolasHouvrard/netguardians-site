package com.logicielpourtous.site.features.ninja;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class NinjaOneClient {

    private final NinjaOneConfigRepository configRepository;
    private final RestClient restClient;

    // Cache du token en mémoire
    private volatile String cachedToken;
    private volatile Instant tokenExpiry = Instant.EPOCH;
    private volatile String cachedInstanceUrl;

    public NinjaOneClient(NinjaOneConfigRepository configRepository) {
        this.configRepository = configRepository;
        this.restClient = RestClient.create();
    }

    // Token OAuth2

    public String getAccessToken(NinjaOneConfig config) {
        if (cachedToken != null
                && Instant.now().isBefore(tokenExpiry)
                && config.getInstanceUrl().equals(cachedInstanceUrl)) {
            return cachedToken;
        }

        String tokenUrl = config.getInstanceUrl().stripTrailing() + "/ws/oauth/token";

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type",    "client_credentials");
        form.add("client_id",     config.getClientId());
        form.add("client_secret", config.getClientSecret());
        form.add("scope",         "monitoring management control");

        TokenResponse resp = restClient.post()
                .uri(tokenUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);

        if (resp == null || resp.accessToken == null) {
            throw new RuntimeException("Réponse token invalide depuis NinjaOne");
        }

        cachedToken       = resp.accessToken;
        cachedInstanceUrl = config.getInstanceUrl();
        tokenExpiry       = Instant.now().plusSeconds(Math.max(resp.expiresIn - 60, 30));
        return cachedToken;
    }

    // Test de connexion

    public int testConnection(NinjaOneConfig config) {
        String token = getAccessToken(config);
        String url   = config.getInstanceUrl().stripTrailing() + "/v2/organizations?pageSize=5";

        List<?> result = restClient.get()
                .uri(url)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(List.class);

        return result != null ? result.size() : 0;
    }

    // Organisations

    public List<NinjaOrganization> getOrganizations(NinjaOneConfig config) {
        String token = getAccessToken(config);
        String url   = config.getInstanceUrl().stripTrailing() + "/v2/organizations";

        NinjaOrganization[] result = restClient.get()
                .uri(url)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(NinjaOrganization[].class);

        return result != null ? List.of(result) : List.of();
    }

    // Organisation par ID

    public NinjaOrganization getOrganization(NinjaOneConfig config, Integer orgId) {
        String token = getAccessToken(config);
        String url   = config.getInstanceUrl().stripTrailing() + "/v2/organization/" + orgId;

        return restClient.get()
                .uri(url)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(NinjaOrganization.class);
    }

    // Création d'organisation

    public NinjaOrganization createOrganization(NinjaOneConfig config, String name) {
        String token = getAccessToken(config);
        String url   = config.getInstanceUrl().stripTrailing() + "/v2/organizations";

        return restClient.post()
                .uri(url)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", name))
                .retrieve()
                .body(NinjaOrganization.class);
    }

    // Appareils par organisation

    public List<NinjaDevice> getDevices(NinjaOneConfig config, Integer orgId) {
        String token = getAccessToken(config);
        String url   = config.getInstanceUrl().stripTrailing()
                       + "/v2/organization/" + orgId + "/devices";

        NinjaDevice[] result = restClient.get()
                .uri(url)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(NinjaDevice[].class);

        return result != null ? List.of(result) : List.of();
    }

    // Lien installeur

    public String getInstallerUrl(NinjaOneConfig config, Integer orgId,
                                  Integer locationId, String installerType) {
        String token = getAccessToken(config);
        String url   = config.getInstanceUrl().stripTrailing()
                       + "/v2/organization/" + orgId
                       + "/location/" + locationId
                       + "/installer/" + installerType;

        Map<?, ?> result = restClient.get()
                .uri(url)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(Map.class);

        return result != null ? (String) result.get("url") : null;
    }

    // DTO interne

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class TokenResponse {
        @JsonProperty("access_token") String accessToken;
        @JsonProperty("expires_in")   int    expiresIn = 3600;
    }
}
