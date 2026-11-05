package com.logicielpourtous.site.features.chat;

import java.time.LocalDateTime;

public record ChatMessageDto(
        Long id,
        SenderType senderType,
        String senderName,
        String content,
        LocalDateTime createdAt
) {
    static ChatMessageDto from(ChatMessage m) {
        return new ChatMessageDto(
                m.getId(),
                m.getSenderType(),
                m.getSenderName(),
                m.getContent(),
                m.getCreatedAt()
        );
    }
}
