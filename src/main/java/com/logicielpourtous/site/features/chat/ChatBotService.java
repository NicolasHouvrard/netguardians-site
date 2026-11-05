package com.logicielpourtous.site.features.chat;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class ChatBotService {

    private final ChatMessageRepository messageRepository;
    private final ChatConversationRepository conversationRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public ChatBotService(ChatMessageRepository messageRepository,
                          ChatConversationRepository conversationRepository,
                          SimpMessagingTemplate messagingTemplate) {
        this.messageRepository      = messageRepository;
        this.conversationRepository = conversationRepository;
        this.messagingTemplate      = messagingTemplate;
    }

    public ChatMessage greetVisitor(ChatConversation conversation) {
        String greeting = "👋 Bonjour" +
                (conversation.getVisitorName() != null ? " " + conversation.getVisitorName() : "") +
                " ! Je suis l'assistant virtuel. Comment puis-je vous aider ?\n\n" +
                "Veuillez choisir une option :\n" +
                "[SUPPORT] Support technique\n" +
                "[COMMERCIAL] Question commerciale\n" +
                "[AUTRE] Autre demande";
        return saveBot(conversation, greeting);
    }

    public ChatMessage route(ChatConversation conversation, String choice) {
        String msg = switch (choice.toUpperCase()) {
            case "SUPPORT"     -> "🔧 Je vous redirige vers notre équipe **Support**. Un technicien va prendre en charge votre demande.";
            case "COMMERCIAL"  -> "💼 Je vous redirige vers notre équipe **Commerciale**. Un conseiller va vous répondre.";
            default            -> "📩 Votre demande a bien été enregistrée. Un membre de notre équipe va vous répondre rapidement.";
        };
        ChatMessage botMsg = saveBot(conversation, msg);
        // Notifier les agents dans l'inbox
        messagingTemplate.convertAndSend("/topic/chat/inbox",
                new InboxNotification(conversation.getId(), "Nouvelle conversation en attente"));
        return botMsg;
    }

    public ChatMessage announceAgent(ChatConversation conversation, String agentName) {
        return saveBot(conversation, "✅ **" + agentName + "** a rejoint la conversation.");
    }

    public ChatMessage sendSystemMessage(ChatConversation conversation, String content) {
        return saveBot(conversation, content);
    }

    private ChatMessage saveBot(ChatConversation conversation, String content) {
        ChatMessage msg = new ChatMessage();
        msg.setConversation(conversation);
        msg.setSenderType(SenderType.BOT);
        msg.setSenderName("Assistant");
        msg.setContent(content);
        msg.setRead(true);
        ChatMessage saved = messageRepository.save(msg);
        conversation.setLastMessageAt(LocalDateTime.now());
        conversationRepository.save(conversation);
        messagingTemplate.convertAndSend("/topic/chat/" + conversation.getId(), ChatMessageDto.from(saved));
        return saved;
    }

    public record InboxNotification(Long conversationId, String message) {}
}
