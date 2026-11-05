package com.logicielpourtous.site.features.mail;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "mail_templates")
@Getter @Setter @NoArgsConstructor
public class MailTemplate {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(unique = true, nullable = false)
    private MailTemplateType type;

    @Column(nullable = false)
    private String subject;

    @Column(name = "html_body", columnDefinition = "TEXT", nullable = false)
    private String htmlBody;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
