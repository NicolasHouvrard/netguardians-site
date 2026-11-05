package com.logicielpourtous.site.features.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Accès aux APIs Microsoft Graph (Mail, Calendar) via OAuth2 (WebClient, pas de SDK tiers).
 */
@Service
public class MicrosoftMailService {

    private static final Logger log = LoggerFactory.getLogger(MicrosoftMailService.class);

    private static final String GRAPH_BASE   = "https://graph.microsoft.com/v1.0/me";
    private static final String TOKEN_BASE   = "https://login.microsoftonline.com";

    private final WebClient webClient;
    private final MailAccountRepository accountRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    public MicrosoftMailService(MailAccountRepository accountRepository) {
        this.accountRepository = accountRepository;
        this.webClient = WebClient.builder()
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                        .build())
                .build();
    }

    // ── Token ─────────────────────────────────────────────────────────────────

    public void refreshTokenIfNeeded(MailAccount account) {
        if (account.getTokenExpiry() != null && account.getTokenExpiry().isAfter(LocalDateTime.now().plusMinutes(5)))
            return;
        if (account.getRefreshToken() == null) return;
        String tenant = account.getMsTenantId() != null ? account.getMsTenantId() : "common";
        String tokenUrl = TOKEN_BASE + "/" + tenant + "/oauth2/v2.0/token";
        try {
            String body = "grant_type=refresh_token"
                    + "&client_id=" + encode(account.getClientId())
                    + "&client_secret=" + encode(account.getClientSecret())
                    + "&refresh_token=" + encode(account.getRefreshToken())
                    + "&scope=" + encode("Mail.ReadWrite Mail.Send Calendars.ReadWrite offline_access");

            String json = webClient.post()
                    .uri(tokenUrl)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode node = mapper.readTree(json);
            account.setAccessToken(node.path("access_token").asText());
            int expiresIn = node.path("expires_in").asInt(3600);
            account.setTokenExpiry(LocalDateTime.now().plusSeconds(expiresIn));
            if (node.has("refresh_token"))
                account.setRefreshToken(node.path("refresh_token").asText());
            accountRepository.save(account);
            log.info("Microsoft token rafraîchi pour {}", account.getUsername());
        } catch (Exception e) {
            log.error("Erreur rafraîchissement token Microsoft pour {} : {}", account.getUsername(), e.getMessage());
        }
    }

    /** Exchange authorization code for access+refresh tokens. */
    public void exchangeCode(MailAccount account, String code, String redirectUri) throws Exception {
        String tenant = account.getMsTenantId() != null ? account.getMsTenantId() : "common";
        String tokenUrl = TOKEN_BASE + "/" + tenant + "/oauth2/v2.0/token";

        String body = "grant_type=authorization_code"
                + "&code=" + encode(code)
                + "&client_id=" + encode(account.getClientId())
                + "&client_secret=" + encode(account.getClientSecret())
                + "&redirect_uri=" + encode(redirectUri)
                + "&scope=" + encode("Mail.ReadWrite Mail.Send Calendars.ReadWrite offline_access");

        String json = webClient.post()
                .uri(tokenUrl)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        JsonNode node = mapper.readTree(json);
        if (node.has("error"))
            throw new RuntimeException("Microsoft token error: " + node.path("error_description").asText());

        account.setAccessToken(node.path("access_token").asText());
        int expiresIn = node.path("expires_in").asInt(3600);
        account.setTokenExpiry(LocalDateTime.now().plusSeconds(expiresIn));
        if (node.has("refresh_token"))
            account.setRefreshToken(node.path("refresh_token").asText());
        accountRepository.save(account);
    }

    // ── Polling mail Graph ─────────────────────────────────────────────────────

    public List<GraphMessage> fetchUnread(MailAccount account) throws Exception {
        refreshTokenIfNeeded(account);
        String json = webClient.get()
                .uri(GRAPH_BASE + "/mailFolders/Inbox/messages?$filter=isRead eq false&$top=50&$select=id,subject,from,toRecipients,body,sentDateTime,internetMessageHeaders,internetMessageId")
                .header("Authorization", "Bearer " + account.getAccessToken())
                .header("Prefer", "outlook.body-content-type=\"text\"")
                .retrieve()
                .bodyToMono(String.class)
                .block();

        JsonNode root = mapper.readTree(json);
        List<GraphMessage> messages = new ArrayList<>();
        JsonNode values = root.path("value");
        if (!values.isArray()) return messages;

        for (JsonNode m : values) {
            GraphMessage gm = new GraphMessage();
            gm.graphId   = m.path("id").asText();
            gm.messageId = m.path("internetMessageId").asText();
            gm.subject   = m.path("subject").asText("(sans objet)");
            gm.from      = m.path("from").path("emailAddress").path("address").asText();
            JsonNode toRec = m.path("toRecipients");
            if (toRec.isArray() && toRec.size() > 0)
                gm.to = toRec.get(0).path("emailAddress").path("address").asText();
            String sentDt = m.path("sentDateTime").asText("");
            if (!sentDt.isEmpty()) {
                try { gm.sentAt = OffsetDateTime.parse(sentDt).toLocalDateTime(); } catch (Exception ignored) {}
            }
            gm.body      = m.path("body").path("content").asText();

            // In-Reply-To depuis internetMessageHeaders
            JsonNode headers = m.path("internetMessageHeaders");
            if (headers.isArray()) {
                for (JsonNode h : headers) {
                    if ("In-Reply-To".equalsIgnoreCase(h.path("name").asText()))
                        gm.inReplyTo = h.path("value").asText();
                }
            }
            messages.add(gm);
        }
        return messages;
    }

    public void markRead(MailAccount account, String graphId) {
        refreshTokenIfNeeded(account);
        try {
            webClient.patch()
                    .uri(GRAPH_BASE + "/messages/" + graphId)
                    .header("Authorization", "Bearer " + account.getAccessToken())
                    .header("Content-Type", "application/json")
                    .bodyValue("{\"isRead\":true}")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (Exception e) {
            log.warn("Erreur markRead Graph {} : {}", graphId, e.getMessage());
        }
    }

    // ── Envoi mail Graph ──────────────────────────────────────────────────────

    public void sendEmail(MailAccount account, String to, String subject, String body,
                          String inReplyToId, String messageId) {
        refreshTokenIfNeeded(account);
        try {
            String fromName = account.getFromName() != null ? account.getFromName() : account.getUsername();
            String bodyEscaped = escapeJson(body);
            String subjEscaped = escapeJson(subject);
            String toEscaped   = escapeJson(to);

            StringBuilder payload = new StringBuilder();
            payload.append("{\"message\":{");
            payload.append("\"subject\":\"").append(subjEscaped).append("\",");
            payload.append("\"body\":{\"contentType\":\"Text\",\"content\":\"").append(bodyEscaped).append("\"},");
            payload.append("\"toRecipients\":[{\"emailAddress\":{\"address\":\"").append(toEscaped).append("\"}}],");
            payload.append("\"from\":{\"emailAddress\":{\"name\":\"").append(escapeJson(fromName)).append("\",\"address\":\"").append(escapeJson(account.getUsername())).append("\"}}");
            if (inReplyToId != null) {
                payload.append(",\"internetMessageHeaders\":[");
                payload.append("{\"name\":\"In-Reply-To\",\"value\":\"").append(escapeJson(inReplyToId)).append("\"},");
                payload.append("{\"name\":\"References\",\"value\":\"").append(escapeJson(inReplyToId)).append("\"},");
                payload.append("{\"name\":\"Message-ID\",\"value\":\"").append(escapeJson(messageId)).append("\"}");
                payload.append("]");
            }
            payload.append("},\"saveToSentItems\":true}");

            webClient.post()
                    .uri(GRAPH_BASE + "/sendMail")
                    .header("Authorization", "Bearer " + account.getAccessToken())
                    .header("Content-Type", "application/json")
                    .bodyValue(payload.toString())
                    .retrieve()
                    .bodyToMono(Void.class)
                    .block();
            log.info("Email Graph envoyé à {}", to);
        } catch (Exception e) {
            log.error("Erreur envoi Graph : {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public void sendHtmlEmail(MailAccount account, String to, String subject, String htmlBody) {
        refreshTokenIfNeeded(account);
        try {
            String fromName    = account.getFromName() != null ? account.getFromName() : account.getUsername();
            String bodyEscaped = escapeJson(htmlBody);
            String subjEscaped = escapeJson(subject);
            String toEscaped   = escapeJson(to);

            StringBuilder payload = new StringBuilder();
            payload.append("{\"message\":{");
            payload.append("\"subject\":\"").append(subjEscaped).append("\",");
            payload.append("\"body\":{\"contentType\":\"HTML\",\"content\":\"").append(bodyEscaped).append("\"},");
            payload.append("\"toRecipients\":[{\"emailAddress\":{\"address\":\"").append(toEscaped).append("\"}}],");
            payload.append("\"from\":{\"emailAddress\":{\"name\":\"").append(escapeJson(fromName))
                   .append("\",\"address\":\"").append(escapeJson(account.getUsername())).append("\"}}");
            payload.append("},\"saveToSentItems\":true}");

            webClient.post()
                    .uri(GRAPH_BASE + "/sendMail")
                    .header("Authorization", "Bearer " + account.getAccessToken())
                    .header("Content-Type", "application/json")
                    .bodyValue(payload.toString())
                    .retrieve()
                    .bodyToMono(Void.class)
                    .block();
            log.info("Email HTML Graph envoyé à {}", to);
        } catch (Exception e) {
            log.error("Erreur envoi HTML Graph : {}", e.getMessage());
        }
    }

    // ── Microsoft Calendar ─────────────────────────────────────────────────────

    public String createCalendarEvent(MailAccount account, String title, String description,
                                      LocalDateTime start, LocalDateTime end) throws Exception {
        refreshTokenIfNeeded(account);
        LocalDateTime endDt = end != null ? end : start.plusHours(1);
        String payload = "{\"subject\":\"" + escapeJson(title) + "\","
                + "\"body\":{\"contentType\":\"Text\",\"content\":\"" + escapeJson(description != null ? description : "") + "\"},"
                + "\"start\":{\"dateTime\":\"" + start + "\",\"timeZone\":\"Europe/Paris\"},"
                + "\"end\":{\"dateTime\":\"" + endDt + "\",\"timeZone\":\"Europe/Paris\"}}";
        String json = webClient.post()
                .uri("https://graph.microsoft.com/v1.0/me/events")
                .header("Authorization", "Bearer " + account.getAccessToken())
                .header("Content-Type", "application/json")
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(String.class)
                .block();
        return mapper.readTree(json).path("id").asText();
    }

    public void updateCalendarEvent(MailAccount account, String eventId, String title, String description,
                                    LocalDateTime start, LocalDateTime end) {
        refreshTokenIfNeeded(account);
        LocalDateTime endDt = end != null ? end : start.plusHours(1);
        String payload = "{\"subject\":\"" + escapeJson(title) + "\","
                + "\"body\":{\"contentType\":\"Text\",\"content\":\"" + escapeJson(description != null ? description : "") + "\"},"
                + "\"start\":{\"dateTime\":\"" + start + "\",\"timeZone\":\"Europe/Paris\"},"
                + "\"end\":{\"dateTime\":\"" + endDt + "\",\"timeZone\":\"Europe/Paris\"}}";
        webClient.patch()
                .uri("https://graph.microsoft.com/v1.0/me/events/" + eventId)
                .header("Authorization", "Bearer " + account.getAccessToken())
                .header("Content-Type", "application/json")
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(Void.class)
                .block();
    }

    public void deleteCalendarEvent(MailAccount account, String eventId) {
        refreshTokenIfNeeded(account);
        try {
            webClient.delete()
                    .uri("https://graph.microsoft.com/v1.0/me/events/" + eventId)
                    .header("Authorization", "Bearer " + account.getAccessToken())
                    .retrieve()
                    .bodyToMono(Void.class)
                    .block();
        } catch (Exception e) {
            log.warn("Microsoft Calendar delete event {} : {}", eventId, e.getMessage());
        }
    }

    public JsonNode listCalendarEvents(MailAccount account, String startDateTime, String endDateTime) {
        refreshTokenIfNeeded(account);
        try {
            String uri = GRAPH_BASE + "/calendarView"
                    + "?startDateTime=" + encode(startDateTime)
                    + "&endDateTime=" + encode(endDateTime)
                    + "&$orderby=start/dateTime"
                    + "&$top=50";
            String json = webClient.get()
                    .uri(uri)
                    .header("Authorization", "Bearer " + account.getAccessToken())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return mapper.readTree(json);
        } catch (Exception e) {
            log.error("Erreur Microsoft Calendar : {}", e.getMessage());
            return mapper.createObjectNode();
        }
    }

    // ── Utilitaires ───────────────────────────────────────────────────────────

    private String encode(String value) {
        if (value == null) return "";
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }

    // ── DTO interne ───────────────────────────────────────────────────────────

    public static class GraphMessage {
        public String graphId;
        public String messageId;
        public String inReplyTo;
        public String subject;
        public String from;
        public String to;
        public String body;
        public LocalDateTime sentAt;
    }
}
