package com.logicielpourtous.site.features.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

@Service
public class ChatAiService {

    private static final Logger log = LoggerFactory.getLogger(ChatAiService.class);
    private static final String MODEL = "gemini-2.0-flash";

    @Value("${gemini.api-key:}")
    private String apiKey;

    private final WebClient                  webClient;
    private final ObjectMapper               mapper;
    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository      messageRepository;

    public ChatAiService(ChatConversationRepository conversationRepository,
                         ChatMessageRepository messageRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository      = messageRepository;
        this.webClient              = WebClient.builder().build();
        this.mapper                 = new ObjectMapper();
    }

    /** Génère une suggestion de réponse (synchrone — utilisé depuis le bouton IA). */
    public String suggestReply(List<ChatMessage> history) {
        if (apiKey == null || apiKey.isBlank()) return "";
        try {
            return callGemini(buildSuggestPrompt(history));
        } catch (Exception e) {
            log.error("Erreur suggestion IA : {}", e.getMessage());
            return "";
        }
    }

    /**
     * Génère le résumé de la conversation et le sauvegarde en base de données.
     * Méthode asynchrone : la conversation est déjà fermée avant l'appel,
     * le résumé arrive quelques secondes après sans bloquer l'agent.
     */
    @Async
    public void summarizeAndSave(Long convId) {
        conversationRepository.findById(convId).ifPresent(conv -> {
            List<ChatMessage> messages = messageRepository.findByConversationOrderByCreatedAtAsc(conv);
            if (apiKey == null || apiKey.isBlank()) return;
            try {
                String summary = callGemini(buildSummaryPrompt(messages));
                conv.setAiSummary(summary);
                conversationRepository.save(conv);
                log.info("Résumé IA sauvegardé pour la conversation {}", convId);
            } catch (Exception e) {
                log.error("Erreur résumé IA (conv {}) : {}", convId, e.getMessage());
            }
        });
    }

    private String callGemini(String prompt) throws Exception {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + MODEL + ":generateContent?key=" + apiKey;

        String body = mapper.writeValueAsString(
                mapper.createObjectNode()
                      .set("contents", mapper.createArrayNode()
                              .add(mapper.createObjectNode()
                                       .set("parts", mapper.createArrayNode()
                                               .add(mapper.createObjectNode()
                                                        .put("text", prompt))))));

        // Retry sur 429 (quota free tier : 15 req/min)
        int[] delays = {5, 15};
        for (int attempt = 0; ; attempt++) {
            try {
                String response = webClient.post()
                        .uri(url)
                        .header("Content-Type", "application/json")
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();
                JsonNode node = mapper.readTree(response);
                return node.path("candidates").get(0)
                           .path("content").path("parts").get(0)
                           .path("text").asText("");
            } catch (Exception e) {
                if (attempt < delays.length && e.getMessage() != null && e.getMessage().contains("429")) {
                    log.warn("Gemini 429 — attente {}s (tentative {}/{})", delays[attempt], attempt + 1, delays.length);
                    Thread.sleep(delays[attempt] * 1000L);
                } else {
                    throw e;
                }
            }
        }
    }

    private String buildSuggestPrompt(List<ChatMessage> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tu es un assistant qui aide un agent de support client à formuler des réponses professionnelles en français.\n\n");
        sb.append("Historique de la conversation :\n");
        for (ChatMessage m : history) {
            String role = switch (m.getSenderType()) {
                case VISITOR -> "Client";
                case USER    -> "Agent";
                case BOT     -> "Bot";
                case AI      -> "IA";
            };
            sb.append(role).append(" : ").append(m.getContent()).append("\n");
        }
        sb.append("\nProposes une réponse courte et professionnelle pour l'agent. Réponds uniquement avec le texte de la réponse, sans introduction.");
        return sb.toString();
    }

    private String buildSummaryPrompt(List<ChatMessage> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("Fais un résumé concis en français de cette conversation de support client (3-5 lignes max) :\n\n");
        for (ChatMessage m : history) {
            String role = switch (m.getSenderType()) {
                case VISITOR -> "Client";
                case USER    -> "Agent";
                case BOT     -> "Bot";
                case AI      -> "IA";
            };
            sb.append(role).append(" : ").append(m.getContent()).append("\n");
        }
        sb.append("\nRésumé :");
        return sb.toString();
    }
}
