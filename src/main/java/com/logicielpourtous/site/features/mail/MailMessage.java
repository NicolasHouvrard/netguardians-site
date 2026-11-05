package com.logicielpourtous.site.features.mail;

import com.logicielpourtous.site.features.request.Request;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "mail_messages")
@Getter @Setter @NoArgsConstructor
public class MailMessage {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** RFC 2822 Message-ID header — unique par email, sert au threading */
    @Column(name = "message_id", unique = true, length = 500)
    private String messageId;

    /** In-Reply-To header — pointe vers le Message-ID parent */
    @Column(name = "in_reply_to", length = 500)
    private String inReplyTo;

    private String subject;

    @Column(name = "from_address")
    private String fromAddress;

    @Column(name = "to_address")
    private String toAddress;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    private Direction direction;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "request_id")
    private Request request;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
    private MailAccount account;

    @PrePersist
    protected void onCreate() { this.processedAt = LocalDateTime.now(); }

    public enum Direction { INBOUND, OUTBOUND }
}
