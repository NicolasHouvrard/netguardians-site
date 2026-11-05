package com.logicielpourtous.site.features.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findByConversationOrderByCreatedAtAsc(ChatConversation conversation);

    @Query("SELECT COUNT(m) FROM ChatMessage m WHERE m.conversation = :conv AND m.read = false AND m.senderType <> com.logicielpourtous.site.features.chat.SenderType.USER")
    long countUnreadByConversation(@Param("conv") ChatConversation conv);

    @Query("SELECT SUM(CASE WHEN m.read = false AND m.senderType <> com.logicielpourtous.site.features.chat.SenderType.USER THEN 1 ELSE 0 END) FROM ChatMessage m WHERE m.conversation.tenant = :#{#conv.tenant} AND m.conversation.status <> com.logicielpourtous.site.features.chat.ConversationStatus.CLOSED")
    Long countTotalUnreadForTenant(@Param("conv") ChatConversation conv);
}
