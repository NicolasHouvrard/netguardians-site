package com.logicielpourtous.site.features.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ClientCodecConfigurer;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import jakarta.mail.internet.MailDateFormat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Accès aux APIs Gmail et Google Calendar via OAuth2 (WebClient, pas de SDK tiers).
 */
@Service
public class GoogleMailService {

    private static final Logger log = LoggerFactory.getLogger(GoogleMailService.class);

    private static final String GMAIL_BASE   = "https://gmail.googleapis.com/gmail/v1/users/me";
    private static final String TOKEN_URL    = "https://oauth2.googleapis.com/token";
    private static final String CALENDAR_BASE = "https://www.googleapis.com/calendar/v3";

    private final WebClient webClient;
    private final MailAccountRepository accountRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    public GoogleMailService(MailAccountRepository accountRepository) {
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
        try {
            String body = "grant_type=refresh_token"
                    + "&client_id=" + encode(account.getClientId())
                    + "&client_secret=" + encode(account.getClientSecret())
                    + "&refresh_token=" + encode(account.getRefreshToken());

            String json = webClient.post()
                    .uri(TOKEN_URL)
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
            log.info("Google token rafraîchi pour {}", account.getUsername());
        } catch (Exception e) {
            log.error("Erreur rafraîchissement token Google pour {} : {}", account.getUsername(), e.getMessage());
        }
    }

    /** Exchange authorization code for access+refresh tokens. */
    public void exchangeCode(MailAccount account, String code, String redirectUri) throws Exception {
        String body = "grant_type=authorization_code"
                + "&code=" + encode(code)
                + "&client_id=" + encode(account.getClientId())
                + "&client_secret=" + encode(account.getClientSecret())
                + "&redirect_uri=" + encode(redirectUri);

        String json = webClient.post()
                .uri(TOKEN_URL)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        JsonNode node = mapper.readTree(json);
        if (node.has("error"))
            throw new RuntimeException("Google token error: " + node.path("error_description").asText());

        account.setAccessToken(node.path("access_token").asText());
        int expiresIn = node.path("expires_in").asInt(3600);
        account.setTokenExpiry(LocalDateTime.now().plusSeconds(expiresIn));
        if (node.has("refresh_token"))
            account.setRefreshToken(node.path("refresh_token").asText());
        accountRepository.save(account);
    }

    // ── Polling Gmail ─────────────────────────────────────────────────────────

    public List<GmailMessage> fetchUnread(MailAccount account) throws Exception {
        refreshTokenIfNeeded(account);
        String token = account.getAccessToken();

        // 1. Lister les IDs de messages non lus depuis le dernier polling
        LocalDateTime since = account.getLastChecked() != null
                ? account.getLastChecked()
                : LocalDateTime.now().minusDays(7);
        long sinceEpoch = since.toEpochSecond(ZoneOffset.UTC);
        String listJson = webClient.get()
                .uri(GMAIL_BASE + "/messages?q=is:unread after:" + sinceEpoch + "&maxResults=50")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        JsonNode listNode = mapper.readTree(listJson);
        List<GmailMessage> messages = new ArrayList<>();
        JsonNode msgs = listNode.path("messages");
        if (!msgs.isArray()) return messages;

        for (JsonNode m : msgs) {
            String msgId = m.path("id").asText();
            try {
                GmailMessage gm = fetchMessage(account, msgId, token);
                if (gm != null) messages.add(gm);
            } catch (Exception e) {
                log.warn("Erreur lecture message Gmail {} : {}", msgId, e.getMessage());
            }
        }
        return messages;
    }

    private GmailMessage fetchMessage(MailAccount account, String gmailId, String token) throws Exception {
        String json = webClient.get()
                .uri(GMAIL_BASE + "/messages/" + gmailId + "?format=full")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        JsonNode node = mapper.readTree(json);
        GmailMessage gm = new GmailMessage();
        gm.gmailId = gmailId;

        JsonNode headers = node.path("payload").path("headers");
        for (JsonNode h : headers) {
            switch (h.path("name").asText()) {
                case "Message-ID"  -> gm.messageId  = h.path("value").asText();
                case "In-Reply-To" -> gm.inReplyTo  = h.path("value").asText();
                case "Subject"     -> gm.subject     = h.path("value").asText();
                case "From"        -> gm.from        = h.path("value").asText();
                case "To"          -> gm.to          = h.path("value").asText();
                case "Date"        -> {
                    try {
                        gm.sentAt = new MailDateFormat().parse(h.path("value").asText())
                                .toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
                    } catch (Exception ignored) {}
                }
            }
        }
        gm.body = extractBody(node.path("payload"));
        collectAttachmentMeta(node.path("payload"), gm.attachments);
        return gm;
    }

    public byte[] fetchAttachmentData(MailAccount account, String gmailId, String attachmentId) {
        refreshTokenIfNeeded(account);
        try {
            String json = webClient.get()
                    .uri(GMAIL_BASE + "/messages/" + gmailId + "/attachments/" + attachmentId)
                    .header("Authorization", "Bearer " + account.getAccessToken())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            JsonNode node = mapper.readTree(json);
            String data = node.path("data").asText("");
            return data.isEmpty() ? null : Base64.getUrlDecoder().decode(data);
        } catch (Exception e) {
            log.error("Erreur téléchargement PJ Gmail {} : {}", attachmentId, e.getMessage());
            return null;
        }
    }

    private void collectAttachmentMeta(JsonNode node, List<GmailAttachment> result) {
        String filename = node.path("filename").asText("");
        if (!filename.isBlank()) {
            GmailAttachment att = new GmailAttachment();
            att.filename  = filename;
            att.mimeType  = node.path("mimeType").asText("application/octet-stream");
            String bodyData = node.path("body").path("data").asText("");
            String attId    = node.path("body").path("attachmentId").asText("");
            if (!bodyData.isEmpty()) {
                att.data = Base64.getUrlDecoder().decode(bodyData);
            } else if (!attId.isEmpty()) {
                att.attachmentId = attId;
            }
            result.add(att);
        }
        JsonNode parts = node.path("parts");
        if (parts.isArray()) {
            for (JsonNode part : parts) collectAttachmentMeta(part, result);
        }
    }

    public void markRead(MailAccount account, String gmailId) {
        refreshTokenIfNeeded(account);
        try {
            webClient.post()
                    .uri(GMAIL_BASE + "/messages/" + gmailId + "/modify")
                    .header("Authorization", "Bearer " + account.getAccessToken())
                    .header("Content-Type", "application/json")
                    .bodyValue("{\"removeLabelIds\":[\"UNREAD\"]}")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (Exception e) {
            log.warn("Erreur markRead Gmail {} : {}", gmailId, e.getMessage());
        }
    }

    // ── Envoi Gmail ───────────────────────────────────────────────────────────

    public void sendEmail(MailAccount account, String to, String subject, String body,
                          String inReplyToId, String messageId) {
        refreshTokenIfNeeded(account);
        try {
            StringBuilder raw = new StringBuilder();
            raw.append("From: ").append(account.getFromName() != null ? account.getFromName() + " <" + account.getUsername() + ">" : account.getUsername()).append("\r\n");
            raw.append("To: ").append(to).append("\r\n");
            raw.append("Subject: ").append(subject).append("\r\n");
            raw.append("Message-ID: ").append(messageId).append("\r\n");
            if (inReplyToId != null) {
                raw.append("In-Reply-To: ").append(inReplyToId).append("\r\n");
                raw.append("References: ").append(inReplyToId).append("\r\n");
            }
            raw.append("Content-Type: text/plain; charset=UTF-8\r\n");
            raw.append("\r\n");
            raw.append(body);

            String encoded = Base64.getUrlEncoder().encodeToString(raw.toString().getBytes(StandardCharsets.UTF_8));
            String payload = "{\"raw\":\"" + encoded + "\"}";

            webClient.post()
                    .uri(GMAIL_BASE + "/messages/send")
                    .header("Authorization", "Bearer " + account.getAccessToken())
                    .header("Content-Type", "application/json")
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            log.info("Email Gmail envoyé à {}", to);
        } catch (Exception e) {
            log.error("Erreur envoi Gmail : {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public void sendHtmlEmail(MailAccount account, String to, String subject, String htmlBody) {
        refreshTokenIfNeeded(account);
        try {
            String msgId = "<" + java.util.UUID.randomUUID() + "@gmail>";
            StringBuilder raw = new StringBuilder();
            raw.append("From: ").append(account.getFromName() != null
                    ? account.getFromName() + " <" + account.getUsername() + ">"
                    : account.getUsername()).append("\r\n");
            raw.append("To: ").append(to).append("\r\n");
            raw.append("Subject: =?UTF-8?B?")
               .append(java.util.Base64.getEncoder().encodeToString(subject.getBytes(StandardCharsets.UTF_8)))
               .append("?=\r\n");
            raw.append("Message-ID: ").append(msgId).append("\r\n");
            raw.append("MIME-Version: 1.0\r\n");
            raw.append("Content-Type: text/html; charset=UTF-8\r\n");
            raw.append("\r\n");
            raw.append(htmlBody);

            String encoded = Base64.getUrlEncoder().encodeToString(raw.toString().getBytes(StandardCharsets.UTF_8));
            String payload = "{\"raw\":\"" + encoded + "\"}";

            webClient.post()
                    .uri(GMAIL_BASE + "/messages/send")
                    .header("Authorization", "Bearer " + account.getAccessToken())
                    .header("Content-Type", "application/json")
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            log.info("Email HTML Gmail envoyé à {}", to);
        } catch (Exception e) {
            log.error("Erreur envoi HTML Gmail : {}", e.getMessage());
        }
    }

    // ── Google Calendar ───────────────────────────────────────────────────────

    public String createCalendarEvent(MailAccount account, String title, String description,
                                      LocalDateTime start, LocalDateTime end) throws Exception {
        refreshTokenIfNeeded(account);
        String startStr = start.atZone(ZoneId.of("Europe/Paris")).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String endStr   = (end != null ? end : start.plusHours(1))
                .atZone(ZoneId.of("Europe/Paris")).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String payload = "{\"summary\":\"" + escapeJson(title) + "\","
                + "\"description\":\"" + escapeJson(description != null ? description : "") + "\","
                + "\"start\":{\"dateTime\":\"" + startStr + "\",\"timeZone\":\"Europe/Paris\"},"
                + "\"end\":{\"dateTime\":\"" + endStr + "\",\"timeZone\":\"Europe/Paris\"}}";
        String json = webClient.post()
                .uri(CALENDAR_BASE + "/calendars/primary/events")
                .header("Authorization", "Bearer " + account.getAccessToken())
                .header("Content-Type", "application/json")
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(String.class)
                .block();
        return mapper.readTree(json).path("id").asText();
    }

    /**
     * Crée un événement Google Calendar avec Google Meet intégré.
     * Retourne un tableau : [eventId, meetLink]
     */
    public String[] createCalendarEventWithMeet(MailAccount account, String title, String description,
                                                 LocalDateTime start, LocalDateTime end,
                                                 String attendeeEmail) throws Exception {
        refreshTokenIfNeeded(account);
        String startStr = start.atZone(ZoneId.of("Europe/Paris")).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String endStr   = (end != null ? end : start.plusHours(1))
                .atZone(ZoneId.of("Europe/Paris")).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String requestId = java.util.UUID.randomUUID().toString();

        StringBuilder payload = new StringBuilder();
        payload.append("{\"summary\":\"").append(escapeJson(title)).append("\",");
        payload.append("\"description\":\"").append(escapeJson(description != null ? description : "")).append("\",");
        payload.append("\"start\":{\"dateTime\":\"").append(startStr).append("\",\"timeZone\":\"Europe/Paris\"},");
        payload.append("\"end\":{\"dateTime\":\"").append(endStr).append("\",\"timeZone\":\"Europe/Paris\"},");
        payload.append("\"conferenceData\":{\"createRequest\":{\"requestId\":\"").append(requestId)
               .append("\",\"conferenceSolutionKey\":{\"type\":\"hangoutsMeet\"}}},");
        if (attendeeEmail != null && !attendeeEmail.isBlank()) {
            payload.append("\"attendees\":[{\"email\":\"").append(escapeJson(attendeeEmail)).append("\"}],");
        }
        payload.append("\"reminders\":{\"useDefault\":true}}");

        String json = webClient.post()
                .uri(uriBuilder -> uriBuilder
                        .scheme("https").host("www.googleapis.com")
                        .path("/calendar/v3/calendars/primary/events")
                        .queryParam("conferenceDataVersion", "1")
                        .build())
                .header("Authorization", "Bearer " + account.getAccessToken())
                .header("Content-Type", "application/json")
                .bodyValue(payload.toString())
                .retrieve()
                .bodyToMono(String.class)
                .block();

        JsonNode node = mapper.readTree(json);
        String eventId  = node.path("id").asText();
        String meetLink = node.path("conferenceData").path("entryPoints").findPath("uri").asText("");
        return new String[]{eventId, meetLink};
    }

    public void updateCalendarEvent(MailAccount account, String eventId, String title, String description,
                                    LocalDateTime start, LocalDateTime end) {
        refreshTokenIfNeeded(account);
        String startStr = start.atZone(ZoneId.of("Europe/Paris")).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String endStr   = (end != null ? end : start.plusHours(1))
                .atZone(ZoneId.of("Europe/Paris")).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String payload = "{\"summary\":\"" + escapeJson(title) + "\","
                + "\"description\":\"" + escapeJson(description != null ? description : "") + "\","
                + "\"start\":{\"dateTime\":\"" + startStr + "\",\"timeZone\":\"Europe/Paris\"},"
                + "\"end\":{\"dateTime\":\"" + endStr + "\",\"timeZone\":\"Europe/Paris\"}}";
        webClient.patch()
                .uri(CALENDAR_BASE + "/calendars/primary/events/" + eventId)
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
                    .uri(CALENDAR_BASE + "/calendars/primary/events/" + eventId)
                    .header("Authorization", "Bearer " + account.getAccessToken())
                    .retrieve()
                    .bodyToMono(Void.class)
                    .block();
        } catch (Exception e) {
            log.warn("Google Calendar delete event {} : {}", eventId, e.getMessage());
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }

    public JsonNode listCalendarEvents(MailAccount account, String timeMin, String timeMax) {
        refreshTokenIfNeeded(account);
        try {
            log.debug("Google Calendar listEvents timeMin={} timeMax={}", timeMin, timeMax);
            String json = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("https")
                            .host("www.googleapis.com")
                            .path("/calendar/v3/calendars/primary/events")
                            .queryParam("timeMin", timeMin)
                            .queryParam("timeMax", timeMax)
                            .queryParam("singleEvents", "true")
                            .queryParam("orderBy", "startTime")
                            .build())
                    .header("Authorization", "Bearer " + account.getAccessToken())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return mapper.readTree(json);
        } catch (WebClientResponseException e) {
            log.error("Erreur Google Calendar {} : {}", e.getStatusCode(), e.getResponseBodyAsString());
            return mapper.createObjectNode();
        } catch (Exception e) {
            log.error("Erreur Google Calendar : {}", e.getMessage());
            return mapper.createObjectNode();
        }
    }

    // ── Utilitaires ───────────────────────────────────────────────────────────

    private String extractBody(JsonNode payload) {
        // text/plain direct
        if ("text/plain".equals(payload.path("mimeType").asText())) {
            String data = payload.path("body").path("data").asText("");
            if (!data.isEmpty()) return new String(Base64.getUrlDecoder().decode(data), StandardCharsets.UTF_8);
        }
        // multipart : cherche text/plain en premier
        JsonNode parts = payload.path("parts");
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                if ("text/plain".equals(part.path("mimeType").asText())) {
                    String data = part.path("body").path("data").asText("");
                    if (!data.isEmpty()) return new String(Base64.getUrlDecoder().decode(data), StandardCharsets.UTF_8);
                }
            }
            // fallback HTML
            for (JsonNode part : parts) {
                String result = extractBody(part);
                if (result != null && !result.isBlank()) return result;
            }
        }
        return "";
    }

    private String extractEmailAddress(String from) {
        if (from == null) return null;
        int lt = from.lastIndexOf('<');
        int gt = from.lastIndexOf('>');
        if (lt >= 0 && gt > lt) return from.substring(lt + 1, gt).trim();
        return from.trim();
    }

    public String extractFrom(String from) { return extractEmailAddress(from); }

    private String encode(String value) {
        if (value == null) return "";
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // ── DTOs internes ─────────────────────────────────────────────────────────

    public static class GmailMessage {
        public String gmailId;
        public String messageId;
        public String inReplyTo;
        public String subject;
        public String from;
        public String to;
        public String body;
        public LocalDateTime sentAt;
        public List<GmailAttachment> attachments = new ArrayList<>();
    }

    public static class GmailAttachment {
        public String filename;
        public String mimeType;
        public String attachmentId; // null si data déjà présent inline
        public byte[] data;         // null si à télécharger via attachmentId
    }
}
