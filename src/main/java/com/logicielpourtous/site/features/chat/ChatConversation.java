package com.logicielpourtous.site.features.chat;

import com.logicielpourtous.site.features.commun.Tenant;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "chat_conversations")
@Getter @Setter @NoArgsConstructor
public class ChatConversation {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConversationType type = ConversationType.PUBLIC;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConversationStatus status = ConversationStatus.PENDING;

    // Pour les visiteurs publics
    @Column(name = "visitor_email")
    private String visitorEmail;

    @Column(name = "visitor_name")
    private String visitorName;

    // Pour les conversations client connecté
    @Column(name = "customer_id")
    private Long customerId;

    // Agent assigné
    @Column(name = "assigned_user_id")
    private Long assignedUserId;

    // Salle Jitsi si visio démarrée
    @Column(name = "video_room_id")
    private String videoRoomId;

    // Résumé IA généré à la clôture
    @Column(name = "ai_summary", columnDefinition = "TEXT")
    private String aiSummary;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt     = LocalDateTime.now();
        this.lastMessageAt = LocalDateTime.now();
    }
}
