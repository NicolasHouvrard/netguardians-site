package com.logicielpourtous.site.features.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

@Service
public class SocialOAuthService {

    private static final Logger log = LoggerFactory.getLogger(SocialOAuthService.class);

    private final SocialNetworkConfigRepository configRepo;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    @Value("${app.oauth.redirect-base:http://localhost:8080}")
    private String redirectBase;

    public SocialOAuthService(SocialNetworkConfigRepository configRepo, ObjectMapper objectMapper) {
        this.configRepo   = configRepo;
        this.objectMapper = objectMapper;
        this.webClient    = WebClient.builder().build();
    }

    public String buildAuthorizationUrl(SocialNetworkConfig config, String state) {
        SocialNetworkType type = config.getNetworkType();
        String redirectUri = buildRedirectUri(type);

        StringBuilder url = new StringBuilder(type.getAuthUrl());
        url.append("?response_type=code");
        url.append("&client_id=").append(encode(config.getClientId()));
        url.append("&redirect_uri=").append(encode(redirectUri));
        url.append("&scope=").append(encode(type.getScope()));
        url.append("&state=").append(encode(state));

        if (type == SocialNetworkType.X) {
            url.append("&code_challenge_method=plain");
            url.append("&code_challenge=").append(encode(state));
        }

        return url.toString();
    }

    @Transactional
    public void exchangeCode(SocialNetworkConfig config, String code) {
        SocialNetworkType type = config.getNetworkType();
        String redirectUri = buildRedirectUri(type);

        try {
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("grant_type", "authorization_code");
            body.add("code", code);
            body.add("redirect_uri", redirectUri);

            WebClient.RequestBodySpec request = webClient.post()
                    .uri(type.getTokenUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED);

            if (type == SocialNetworkType.X) {
                String credentials = Base64.getEncoder().encodeToString(
                        (config.getClientId() + ":" + config.getClientSecret()).getBytes(StandardCharsets.UTF_8));
                request = (WebClient.RequestBodySpec) request.header("Authorization", "Basic " + credentials);
                body.add("code_verifier", generateState(config));
            } else {
                body.add("client_id", config.getClientId());
                body.add("client_secret", config.getClientSecret());
            }

            String response = request
                    .body(BodyInserters.fromFormData(body))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode json = objectMapper.readTree(response);
            String grantedScopes = json.path("scope").asText("(non communiqué)");
            log.info("OAuth token reçu pour config id={} network={} — scopes accordés : {}",
                    config.getId(), type, grantedScopes);
            config.setAccessToken(json.path("access_token").asText(null));
            if (json.has("refresh_token")) {
                config.setRefreshToken(json.path("refresh_token").asText(null));
            }
            if (json.has("expires_in")) {
                config.setTokenExpiresAt(LocalDateTime.now().plusSeconds(json.path("expires_in").asLong(3600)));
            }

            // LinkedIn perso : récupère le person ID via /oidc/userinfo (compatible openid+profile)
            if (type == SocialNetworkType.LINKEDIN) {
                try {
                    String userInfo = webClient.get()
                            .uri("https://api.linkedin.com/oidc/userinfo")
                            .header("Authorization", "Bearer " + config.getAccessToken())
                            .retrieve()
                            .bodyToMono(String.class)
                            .block();
                    log.info("LinkedIn /oidc/userinfo response: {}", userInfo);
                    String sub = objectMapper.readTree(userInfo).path("sub").asText(null);
                    if (sub != null && !sub.isBlank()) {
                        config.setAccountHandle(sub);
                        log.info("LinkedIn person sub résolu : {}", sub);
                    } else {
                        log.warn("LinkedIn /oidc/userinfo : champ 'sub' absent dans : {}", userInfo);
                    }
                } catch (Exception ex) {
                    log.warn("LinkedIn /oidc/userinfo failed : {}", ex.getMessage());
                }
            }

            configRepo.save(config);
            log.info("OAuth token exchanged for config id={} network={}", config.getId(), type);

        } catch (Exception e) {
            log.error("OAuth exchange failed for config id={}: {}", config.getId(), e.getMessage());
            throw new RuntimeException("Échec de l'échange OAuth : " + e.getMessage(), e);
        }
    }

    @Transactional
    public void refreshTokenIfNeeded(SocialNetworkConfig config) {
        if (config.getRefreshToken() == null || config.getTokenExpiresAt() == null) return;
        if (config.getTokenExpiresAt().isAfter(LocalDateTime.now().plusMinutes(5))) return;

        SocialNetworkType type = config.getNetworkType();
        try {
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("grant_type", "refresh_token");
            body.add("refresh_token", config.getRefreshToken());
            body.add("client_id", config.getClientId());
            body.add("client_secret", config.getClientSecret());

            String response = webClient.post()
                    .uri(type.getTokenUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(body))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode json = objectMapper.readTree(response);
            config.setAccessToken(json.path("access_token").asText(null));
            if (json.has("refresh_token")) config.setRefreshToken(json.path("refresh_token").asText(null));
            if (json.has("expires_in"))
                config.setTokenExpiresAt(LocalDateTime.now().plusSeconds(json.path("expires_in").asLong(3600)));
            configRepo.save(config);
            log.info("Token refreshed for config id={}", config.getId());

        } catch (Exception e) {
            log.warn("Token refresh failed for config id={}: {}", config.getId(), e.getMessage());
        }
    }

    public String buildRedirectUri(SocialNetworkType type) {
        return redirectBase + "/intra/social/oauth/" + type.name().toLowerCase() + "/callback";
    }

    public String generateState(SocialNetworkConfig config) {
        return config.getId() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String encode(String value) {
        if (value == null) return "";
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
