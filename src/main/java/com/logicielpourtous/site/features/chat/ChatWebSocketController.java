package com.logicielpourtous.site.features.chat;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.time.LocalDateTime;

@Controller
public class ChatWebSocketController {

    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ChatBotService botService;

    public ChatWebSocketController(ChatConversationRepository conversationRepository,
                                   ChatMessageRepository messageRepository,
                                   SimpMessagingTemplate messagingTemplate,
                                   ChatBotService botService) {
        this.conversationRepository = conversationRepository;
        this.messageRepository      = messageRepository;
        this.messagingTemplate      = messagingTemplate;
        this.botService             = botService;
    }

    @MessageMapping("/chat/{id}/send")
    public void sendMessage(@DestinationVariable Long id,
                            @Payload MessagePayload payload,
                            Authentication auth) {
        ChatConversation conv = conversationRepository.findById(id).orElse(null);
        if (conv == null || conv.getStatus() == ConversationStatus.CLOSED) return;

        ChatMessage msg = new ChatMessage();
        msg.setConversation(conv);
        msg.setContent(payload.content());

        if (auth != null && auth.getPrincipal() instanceof CustomUserDetails user) {
            // Un compte en attente de choix de societe a un customerId nul sans etre un compte interne :
            // il n'est jamais un agent (ni prise de conversation, ni message en tant qu'agent).
            boolean isAgent = !user.selectionRequise() && (conv.getType() == ConversationType.INTERNAL
                    || (conv.getAssignedUserId() != null && conv.getAssignedUserId().equals(user.getUserId()))
                    || (conv.getStatus() == ConversationStatus.PENDING && user.getCustomerId() == null));
            if (isAgent) {
                msg.setSenderType(SenderType.USER);
                msg.setSenderId(user.getUserId());
                msg.setSenderName(user.getFullName() != null ? user.getFullName() : user.getUsername());
                if (conv.getStatus() == ConversationStatus.PENDING) {
                    conv.setStatus(ConversationStatus.OPEN);
                    conv.setAssignedUserId(user.getUserId());
                }
            } else {
                msg.setSenderType(SenderType.VISITOR);
                msg.setSenderName(user.getFullName() != null ? user.getFullName() : user.getUsername());
            }
        } else {
            msg.setSenderType(SenderType.VISITOR);
            msg.setSenderName(conv.getVisitorName() != null ? conv.getVisitorName() : conv.getVisitorEmail());
        }

        ChatMessage saved = messageRepository.save(msg);
        conv.setLastMessageAt(LocalDateTime.now());
        conversationRepository.save(conv);

        messagingTemplate.convertAndSend("/topic/chat/" + id, ChatMessageDto.from(saved));

        // Notifier l'inbox agents si message visiteur/client
        if (saved.getSenderType() == SenderType.VISITOR) {
            messagingTemplate.convertAndSend("/topic/chat/inbox",
                    new ChatBotService.InboxNotification(id, "Nouveau message"));
        }
    }

    @MessageMapping("/chat/{id}/typing")
    public void typing(@DestinationVariable Long id, @Payload TypingPayload payload) {
        messagingTemplate.convertAndSend("/topic/chat/typing/" + id, payload);
    }

    @MessageMapping("/chat/{id}/bot-choice")
    public void botChoice(@DestinationVariable Long id, @Payload BotChoicePayload payload) {
        conversationRepository.findById(id).ifPresent(conv -> botService.route(conv, payload.choice()));
    }

    public record MessagePayload(String content) {}
    public record TypingPayload(String senderName, boolean typing) {}
    public record BotChoicePayload(String choice) {}
}
