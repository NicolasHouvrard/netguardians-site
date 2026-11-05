package com.logicielpourtous.site.features.request;

import com.logicielpourtous.site.features.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "ticket_attachments")
@Getter @Setter @NoArgsConstructor
public class TicketAttachment {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "request_id", nullable = false)
    private Request request;

    @Column(nullable = false)
    private String filename; // full URL path: /uploads/tickets/{requestId}/{uuid.ext}

    @Column(name = "original_name")
    private String originalName;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "file_size")
    private Long fileSize;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by_user_id")
    private User uploadedBy;

    @Column(name = "uploaded_at")
    private LocalDateTime uploadedAt;

    @PrePersist
    protected void onCreate() { this.uploadedAt = LocalDateTime.now(); }

    public boolean isImage() {
        return contentType != null && contentType.startsWith("image/");
    }

    public String getDisplaySize() {
        if (fileSize == null) return "";
        if (fileSize < 1024) return fileSize + " o";
        if (fileSize < 1024 * 1024) return (fileSize / 1024) + " Ko";
        return String.format("%.1f Mo", fileSize / (1024.0 * 1024));
    }
}
