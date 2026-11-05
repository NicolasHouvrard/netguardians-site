package com.logicielpourtous.site.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Service
public class CaptchaService {

    private static final Logger log = LoggerFactory.getLogger(CaptchaService.class);

    private static final String VERIFY_URL =
            "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    @Value("${captcha.enabled:false}")
    private boolean enabled;

    @Value("${captcha.secret-key:}")
    private String secretKey;

    private final WebClient    webClient;
    private final ObjectMapper objectMapper;

    public CaptchaService(ObjectMapper objectMapper) {
        this.webClient    = WebClient.builder().build();
        this.objectMapper = objectMapper;
    }

    public boolean isEnabled() { return enabled; }

    /**
     * Valide le token Turnstile auprès de l'API Cloudflare.
     * Retourne toujours true si captcha.enabled=false.
     */
    public boolean verify(String token, String remoteIp) {
        if (!enabled) return true;
        if (token == null || token.isBlank()) {
            log.warn("Turnstile : token absent");
            return false;
        }
        try {
            Map<String, String> body = secretKey != null && !secretKey.isBlank()
                    ? Map.of("secret", secretKey, "response", token, "remoteip", remoteIp != null ? remoteIp : "")
                    : Map.of("secret", "", "response", token);

            String response = webClient.post()
                    .uri(VERIFY_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            boolean success = objectMapper.readTree(response).path("success").asBoolean(false);
            if (!success) log.warn("Turnstile échec : {}", response);
            return success;

        } catch (Exception e) {
            log.error("Turnstile erreur de vérification : {}", e.getMessage());
            return false;
        }
    }
}
