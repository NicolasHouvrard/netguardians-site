package com.logicielpourtous.site.features.api.angular;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Service
public class GoogleAuthService {

    private static final Logger log = LoggerFactory.getLogger(GoogleAuthService.class);
    private static final String TOKENINFO_URL = "https://oauth2.googleapis.com/tokeninfo?id_token=";

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Vérifie un Google ID token (credential GIS) via l'endpoint tokeninfo de Google.
     * Retourne null si le token est invalide ou l'email non vérifié.
     */
    @SuppressWarnings("unchecked")
    public GoogleUserInfo verify(String idToken) {
        try {
            Map<String, String> info = restTemplate.getForObject(TOKENINFO_URL + idToken, Map.class);
            if (info == null || !"true".equals(info.get("email_verified"))) return null;
            return new GoogleUserInfo(info.get("sub"), info.get("email"), info.get("name"));
        } catch (Exception e) {
            log.warn("Vérification token Google échouée : {}", e.getMessage());
            return null;
        }
    }

    public record GoogleUserInfo(String sub, String email, String name) {}
}
