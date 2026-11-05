package com.logicielpourtous.site.features.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private static final Set<String> LOCAL_PREFIXES = Set.of(
            "127.", "0:0:0:0:0:0:0:1", "::1", "192.168.", "10.", "172.16.", "172.17.",
            "172.18.", "172.19.", "172.20.", "172.21.", "172.22.", "172.23.", "172.24.",
            "172.25.", "172.26.", "172.27.", "172.28.", "172.29.", "172.30.", "172.31.");

    private final AuditLogRepository repo;
    private final ObjectMapper        objectMapper;
    private final WebClient           webClient;

    public AuditLogService(AuditLogRepository repo, ObjectMapper objectMapper) {
        this.repo         = repo;
        this.objectMapper = objectMapper;
        this.webClient    = WebClient.builder().build();
    }

    @Async
    public void logLogin(Long userId, String email, String ip, String userAgent, boolean success) {
        AuditLog entry = new AuditLog();
        entry.setType(success ? AuditLog.Type.LOGIN_SUCCESS : AuditLog.Type.LOGIN_FAILURE);
        entry.setUserId(userId);
        entry.setEmail(email);
        entry.setIp(ip);
        entry.setUserAgent(userAgent);
        entry.setTimestamp(LocalDateTime.now());
        enrichGeo(entry, ip);
        repo.save(entry);
    }

    @Async
    public void logPageVisit(Long userId, String email, String url, String ip, String userAgent) {
        AuditLog entry = new AuditLog();
        entry.setType(AuditLog.Type.PAGE_VISIT);
        entry.setUserId(userId);
        entry.setEmail(email);
        entry.setIp(ip);
        entry.setUrl(url);
        entry.setUserAgent(userAgent);
        entry.setTimestamp(LocalDateTime.now());
        repo.save(entry);
    }

    public List<AuditLog> search(String typeStr, String email, String ip, int limit) {
        int fetch = Math.min(limit * 5, 5000);
        String emailLow = (email != null && !email.isBlank()) ? email.toLowerCase() : null;
        AuditLog.Type typeFilter = null;
        if (typeStr != null && !typeStr.isBlank()) {
            try { typeFilter = AuditLog.Type.valueOf(typeStr.toUpperCase()); } catch (Exception ignored) {}
        }
        final AuditLog.Type finalType = typeFilter;
        return repo.findAllByOrderByTimestampDesc(PageRequest.of(0, fetch))
                .stream()
                .filter(a -> {
                    if (finalType != null && a.getType() != finalType) return false;
                    if (emailLow  != null && (a.getEmail() == null || !a.getEmail().toLowerCase().contains(emailLow))) return false;
                    if (ip != null && !ip.isBlank() && (a.getIp() == null || !a.getIp().startsWith(ip))) return false;
                    return true;
                })
                .limit(limit)
                .toList();
    }

    // ── GeoIP via ip-api.com (gratuit, HTTP) ─────────────────────────────────

    private void enrichGeo(AuditLog entry, String ip) {
        if (ip == null) return;
        if (LOCAL_PREFIXES.stream().anyMatch(ip::startsWith)) {
            entry.setCountry("Local");
            return;
        }
        try {
            String response = webClient.get()
                    .uri("http://ip-api.com/json/" + ip + "?fields=status,country,countryCode,city&lang=fr")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(3))
                    .block();
            JsonNode json = objectMapper.readTree(response);
            if ("success".equals(json.path("status").asText())) {
                entry.setCountry(json.path("country").asText(null));
                entry.setCountryCode(json.path("countryCode").asText(null));
                entry.setCity(json.path("city").asText(null));
            }
        } catch (Exception e) {
            log.debug("GeoIP lookup failed for {}: {}", ip, e.getMessage());
        }
    }
}
