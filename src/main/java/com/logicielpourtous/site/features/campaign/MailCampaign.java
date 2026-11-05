package com.logicielpourtous.site.features.campaign;

import com.logicielpourtous.site.features.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "mail_campaigns")
@Getter @Setter @NoArgsConstructor
public class MailCampaign {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String subject;

    @Column(name = "html_content", columnDefinition = "TEXT")
    private String htmlContent;

    @Enumerated(EnumType.STRING)
    private CampaignStatus status = CampaignStatus.DRAFT;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private User createdBy;

    @Column(name = "scheduled_at")
    private LocalDateTime scheduledAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "recipient_count")
    private Integer recipientCount = 0;

    @Column(name = "open_count")
    private Integer openCount = 0;

    @Column(name = "click_count")
    private Integer clickCount = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); }

    public enum CampaignStatus {
        DRAFT("Brouillon"), SCHEDULED("Planifiée"), SENT("Envoyée"), FAILED("Échec");
        public final String label;
        public final String badgeCss;
        CampaignStatus(String label) {
            this.label = label;
            this.badgeCss = switch(this.name()) {
                case "DRAFT"      -> "text-bg-secondary";
                case "SCHEDULED"  -> "text-bg-warning";
                case "SENT"       -> "text-bg-success";
                case "FAILED"     -> "text-bg-danger";
                default           -> "text-bg-secondary";
            };
        }
    }
}
