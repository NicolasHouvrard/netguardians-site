package com.logicielpourtous.site.features.chat;

import com.logicielpourtous.site.features.auth.CustomUserDetails;
import com.logicielpourtous.site.features.commun.Tenant;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class ChatIntraAdvice {

    private final ChatConversationRepository conversationRepository;

    public ChatIntraAdvice(ChatConversationRepository conversationRepository) {
        this.conversationRepository = conversationRepository;
    }

    @ModelAttribute
    public void prepareChatTray(Authentication auth,
                                HttpServletRequest request,
                                Model model) {
        if (auth == null || !(auth.getPrincipal() instanceof CustomUserDetails user)) return;
        if (!request.getRequestURI().startsWith("/intra")) return;
        Tenant tenant = user.getTenant();
        if (tenant == null) return;
        long pending = conversationRepository.countByTenantAndStatus(tenant, ConversationStatus.PENDING);
        long open    = conversationRepository.countByTenantAndStatus(tenant, ConversationStatus.OPEN);
        model.addAttribute("chatActiveCount", pending + open);
        model.addAttribute("chatPendingCount", pending);
    }
}
