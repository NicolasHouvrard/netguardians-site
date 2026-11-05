package com.logicielpourtous.site.features.chat;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
public class PublicChatController {

    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository      messageRepository;
    private final ChatBotService             botService;
    private final TenantRepository           tenantRepository;

    public PublicChatController(ChatConversationRepository conversationRepository,
                                ChatMessageRepository messageRepository,
                                ChatBotService botService,
                                TenantRepository tenantRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository      = messageRepository;
        this.botService             = botService;
        this.tenantRepository       = tenantRepository;
    }

    // ── Chat public ───────────────────────────────────────────────────────────

    @GetMapping("/chat")
    public String publicChat(@RequestParam(required = false) Long convId, Model model) {
        if (convId != null) {
            conversationRepository.findById(convId).ifPresent(c -> {
                model.addAttribute("conv", c);
                model.addAttribute("messages", messageRepository.findByConversationOrderByCreatedAtAsc(c));
            });
        }
        return "public/chat";
    }

    @PostMapping("/chat/start")
    public String startPublicChat(@RequestParam String email,
                                  @RequestParam(required = false) String name,
                                  HttpSession session) {
        ChatConversation existing = conversationRepository
                .findByVisitorEmailAndStatusNot(email.trim().toLowerCase(), ConversationStatus.CLOSED)
                .orElse(null);
        if (existing != null) {
            session.setAttribute("chatConvId", existing.getId());
            return "redirect:/chat?convId=" + existing.getId();
        }

        Tenant tenant = tenantRepository.findAll().stream().findFirst().orElse(null);
        if (tenant == null) return "redirect:/chat";

        ChatConversation conv = new ChatConversation();
        conv.setTenant(tenant);
        conv.setType(ConversationType.PUBLIC);
        conv.setStatus(ConversationStatus.PENDING);
        conv.setVisitorEmail(email.trim().toLowerCase());
        conv.setVisitorName(name != null && !name.isBlank() ? name.trim() : null);
        conversationRepository.save(conv);
        botService.greetVisitor(conv);

        session.setAttribute("chatConvId", conv.getId());
        return "redirect:/chat?convId=" + conv.getId();
    }

    // ── Widget flottant ───────────────────────────────────────────────────────

    @GetMapping("/chat/widget")
    public String chatWidget(HttpSession session, Model model) {
        Long convId = (Long) session.getAttribute("chatConvId");
        if (convId != null) {
            ChatConversation conv = conversationRepository.findById(convId).orElse(null);
            if (conv != null && conv.getStatus() != ConversationStatus.CLOSED) {
                model.addAttribute("conv", conv);
                model.addAttribute("messages", messageRepository.findByConversationOrderByCreatedAtAsc(conv));
            }
        }
        return "public/chat-widget :: panel";
    }

    @PostMapping("/chat/widget/start")
    public String startWidgetChat(@RequestParam String email,
                                  @RequestParam(required = false) String name,
                                  HttpSession session, Model model) {
        ChatConversation existing = conversationRepository
                .findByVisitorEmailAndStatusNot(email.trim().toLowerCase(), ConversationStatus.CLOSED)
                .orElse(null);
        ChatConversation conv;
        if (existing != null) {
            conv = existing;
        } else {
            Tenant tenant = tenantRepository.findAll().stream().findFirst().orElse(null);
            if (tenant == null) return "public/chat-widget :: panel";

            conv = new ChatConversation();
            conv.setTenant(tenant);
            conv.setType(ConversationType.PUBLIC);
            conv.setStatus(ConversationStatus.PENDING);
            conv.setVisitorEmail(email.trim().toLowerCase());
            conv.setVisitorName(name != null && !name.isBlank() ? name.trim() : null);
            conversationRepository.save(conv);
            botService.greetVisitor(conv);
        }

        session.setAttribute("chatConvId", conv.getId());
        model.addAttribute("conv", conv);
        model.addAttribute("messages", messageRepository.findByConversationOrderByCreatedAtAsc(conv));
        return "public/chat-widget :: panel";
    }

    // ── Chat espace client ────────────────────────────────────────────────────

    @GetMapping("/espace/chat")
    public String espaceChat(Model model, @AuthenticationPrincipal CustomUserDetails current) {
        List<ChatConversation> conversations = current.getCustomerId() != null
                ? conversationRepository.findByTenantAndCustomerId(current.getTenant(), current.getCustomerId())
                : List.of();
        model.addAttribute("conversations", conversations);
        return "espace/chat/list";
    }

    @PostMapping("/espace/chat/start")
    public String startEspaceChat(@AuthenticationPrincipal CustomUserDetails current) {
        // Reprendre une conversation ouverte si elle existe
        if (current.getCustomerId() != null) {
            List<ChatConversation> existing = conversationRepository
                    .findByTenantAndCustomerId(current.getTenant(), current.getCustomerId());
            ChatConversation open = existing.stream()
                    .filter(c -> c.getStatus() != ConversationStatus.CLOSED)
                    .findFirst().orElse(null);
            if (open != null) return "redirect:/espace/chat/" + open.getId();
        }

        ChatConversation conv = new ChatConversation();
        conv.setTenant(current.getTenant());
        conv.setType(ConversationType.CLIENT);
        conv.setStatus(ConversationStatus.PENDING);
        conv.setCustomerId(current.getCustomerId());
        conv.setVisitorName(current.getFullName());
        conv.setVisitorEmail(current.getUsername());
        conversationRepository.save(conv);

        botService.greetVisitor(conv);

        return "redirect:/espace/chat/" + conv.getId();
    }

    @GetMapping("/espace/chat/{id}")
    public String espaceChatDetail(@PathVariable Long id, Model model,
                                   @AuthenticationPrincipal CustomUserDetails current) {
        ChatConversation conv = conversationRepository.findById(id).orElse(null);
        if (conv == null) return "redirect:/espace/chat";

        List<ChatMessage> messages = messageRepository.findByConversationOrderByCreatedAtAsc(conv);
        // Marquer les messages agents comme lus côté client
        messages.stream().filter(m -> !m.isRead() && m.getSenderType() == SenderType.USER)
                .forEach(m -> { m.setRead(true); messageRepository.save(m); });

        model.addAttribute("conv",     conv);
        model.addAttribute("messages", messages);
        return "espace/chat/detail";
    }
}
