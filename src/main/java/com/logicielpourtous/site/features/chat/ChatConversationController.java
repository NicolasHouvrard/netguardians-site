package com.logicielpourtous.site.features.chat;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.customers.CustomerRepository;
import com.logicielpourtous.site.features.user.User;
import com.logicielpourtous.site.features.user.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/intra/chat")
public class ChatConversationController {

    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository      messageRepository;
    private final ChatBotService             botService;
    private final ChatAiService              aiService;
    private final ChatTranscriptService      transcriptService;
    private final UserRepository             userRepository;
    private final CustomerRepository         customerRepository;

    public ChatConversationController(ChatConversationRepository conversationRepository,
                                      ChatMessageRepository messageRepository,
                                      ChatBotService botService,
                                      ChatAiService aiService,
                                      ChatTranscriptService transcriptService,
                                      UserRepository userRepository,
                                      CustomerRepository customerRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository      = messageRepository;
        this.botService             = botService;
        this.aiService              = aiService;
        this.transcriptService      = transcriptService;
        this.userRepository         = userRepository;
        this.customerRepository     = customerRepository;
    }

    // ── Inbox ─────────────────────────────────────────────────────────────────

    @GetMapping
    public String inbox(Model model, @AuthenticationPrincipal CustomUserDetails current) {
        loadSidebar(model, current);
        return "intra/chat/inbox";
    }

    // ── Détail conversation ───────────────────────────────────────────────────

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model,
                         @AuthenticationPrincipal CustomUserDetails current) {
        loadSidebar(model, current);
        model.addAttribute("activeConvId", id);
        ChatConversation conv = conversationRepository.findById(id).orElse(null);
        if (conv == null) return "redirect:/intra/chat";

        // Marquer les messages comme lus
        List<ChatMessage> messages = messageRepository.findByConversationOrderByCreatedAtAsc(conv);
        messages.stream().filter(m -> !m.isRead() && m.getSenderType() != SenderType.USER)
                .forEach(m -> { m.setRead(true); messageRepository.save(m); });

        // Si l'agent arrive sur une conv PENDING → l'ouvrir + annoncer
        if (conv.getStatus() == ConversationStatus.PENDING) {
            conv.setStatus(ConversationStatus.OPEN);
            conv.setAssignedUserId(current.getUserId());
            conversationRepository.save(conv);
            botService.announceAgent(conv, current.getFullName() != null ? current.getFullName() : current.getUsername());
            messages = messageRepository.findByConversationOrderByCreatedAtAsc(conv);
        }

        // Lier au client par email si trouvé
        if (conv.getVisitorEmail() != null) {
            customerRepository.findByEmail(conv.getVisitorEmail())
                    .ifPresent(c -> model.addAttribute("linkedCustomer", c));
        }
        if (conv.getCustomerId() != null && !model.containsAttribute("linkedCustomer")) {
            customerRepository.findById(conv.getCustomerId())
                    .ifPresent(c -> model.addAttribute("linkedCustomer", c));
        }

        model.addAttribute("conv",     conv);
        model.addAttribute("messages", messages);
        return "intra/chat/inbox";
    }

    // ── Tray (pastille flottante) ─────────────────────────────────────────────

    @GetMapping("/tray")
    public String tray(Model model, @AuthenticationPrincipal CustomUserDetails current) {
        loadSidebar(model, current);
        return "intra/chat/tray :: panel";
    }

    // ── Fermer ────────────────────────────────────────────────────────────────

    @PostMapping("/{id}/close")
    public String close(@PathVariable Long id,
                        @RequestParam(defaultValue = "false") boolean sendTranscript,
                        RedirectAttributes ra) {
        conversationRepository.findById(id).ifPresent(conv -> {
            // Fermeture immédiate — le résumé IA arrive en arrière-plan
            conv.setStatus(ConversationStatus.CLOSED);
            conv.setClosedAt(LocalDateTime.now());
            conversationRepository.save(conv);

            // Résumé asynchrone : ne bloque pas l'agent
            aiService.summarizeAndSave(id);

            if (sendTranscript) {
                String email = conv.getVisitorEmail();
                if (email != null) transcriptService.sendTranscript(conv, email);
            }
        });
        ra.addFlashAttribute("successMessage", "Conversation fermée. Le résumé IA sera disponible dans quelques secondes.");
        return "redirect:/intra/chat";
    }

    // ── Transcript ────────────────────────────────────────────────────────────

    @PostMapping("/{id}/transcript")
    public String transcript(@PathVariable Long id,
                             @RequestParam String email,
                             RedirectAttributes ra) {
        conversationRepository.findById(id).ifPresent(conv -> transcriptService.sendTranscript(conv, email));
        ra.addFlashAttribute("successMessage", "Transcription envoyée.");
        return "redirect:/intra/chat/" + id;
    }

    // ── Visio Jitsi ───────────────────────────────────────────────────────────

    @PostMapping("/{id}/video")
    public String startVideo(@PathVariable Long id, RedirectAttributes ra) {
        conversationRepository.findById(id).ifPresent(conv -> {
            if (conv.getVideoRoomId() == null) {
                String roomId = "ng-" + id + "-" + UUID.randomUUID().toString().substring(0, 6);
                conv.setVideoRoomId(roomId);
                conversationRepository.save(conv);
                botService.sendSystemMessage(conv,
                        "📹 Rejoignez la visio : https://meet.jit.si/" + roomId);
            }
        });
        return "redirect:/intra/chat/" + id;
    }

    // ── Suggestion IA (réponse JSON pour HTMX) ───────────────────────────────

    @PostMapping("/{id}/ai-suggest")
    @ResponseBody
    public ResponseEntity<Map<String, String>> aiSuggest(@PathVariable Long id) {
        ChatConversation conv = conversationRepository.findById(id).orElse(null);
        if (conv == null) return ResponseEntity.notFound().build();
        List<ChatMessage> messages = messageRepository.findByConversationOrderByCreatedAtAsc(conv);
        String suggestion = aiService.suggestReply(messages);
        return ResponseEntity.ok(Map.of("suggestion", suggestion));
    }

    // ── Assigner ─────────────────────────────────────────────────────────────

    @PostMapping("/{id}/assign")
    public String assign(@PathVariable Long id,
                         @AuthenticationPrincipal CustomUserDetails current,
                         RedirectAttributes ra) {
        conversationRepository.findById(id).ifPresent(conv -> {
            conv.setAssignedUserId(current.getUserId());
            conv.setStatus(ConversationStatus.OPEN);
            conversationRepository.save(conv);
            botService.announceAgent(conv, current.getFullName() != null ? current.getFullName() : current.getUsername());
        });
        return "redirect:/intra/chat/" + id;
    }

    // ── Helper sidebar ────────────────────────────────────────────────────────

    private void loadSidebar(Model model, CustomUserDetails current) {
        List<ChatConversation> open    = conversationRepository.findByTenantAndStatusOrderByLastMessageAtDesc(
                current.getTenant(), ConversationStatus.OPEN);
        List<ChatConversation> pending = conversationRepository.findByTenantAndStatusOrderByLastMessageAtDesc(
                current.getTenant(), ConversationStatus.PENDING);
        List<ChatConversation> closed  = conversationRepository.findByTenantAndStatusOrderByLastMessageAtDesc(
                current.getTenant(), ConversationStatus.CLOSED);

        Map<Long, Long> unreadCounts = conversationRepository
                .findByTenantAndStatusNotOrderByLastMessageAtDesc(current.getTenant(), ConversationStatus.CLOSED)
                .stream().collect(Collectors.toMap(
                        ChatConversation::getId,
                        c -> messageRepository.countUnreadByConversation(c)));

        List<Long> userIds = open.stream().filter(c -> c.getAssignedUserId() != null)
                .map(ChatConversation::getAssignedUserId).distinct().toList();
        Map<Long, String> userNames = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u.getFullName() != null ? u.getFullName() : u.getEmail()));

        // Conversations "en ligne" = activité dans les 10 dernières minutes
        java.time.LocalDateTime cutoff = java.time.LocalDateTime.now().minusMinutes(10);
        java.util.Set<Long> onlineIds = java.util.stream.Stream.concat(open.stream(), pending.stream())
                .filter(c -> c.getLastMessageAt() != null && c.getLastMessageAt().isAfter(cutoff))
                .map(ChatConversation::getId)
                .collect(java.util.stream.Collectors.toSet());

        model.addAttribute("open",         open);
        model.addAttribute("pending",      pending);
        model.addAttribute("closed",       closed);
        model.addAttribute("unreadCounts", unreadCounts);
        model.addAttribute("userNames",    userNames);
        model.addAttribute("onlineIds",    onlineIds);
        model.addAttribute("activeConvId", null);
    }
}
