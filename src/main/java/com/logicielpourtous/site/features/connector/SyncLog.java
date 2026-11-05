package com.logicielpourtous.site.features.connector;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "sync_logs", indexes = {
    @Index(name = "idx_sync_log_connector", columnList = "connector_id"),
    @Index(name = "idx_sync_log_started",   columnList = "started_at")
})
@Getter @Setter @NoArgsConstructor
public class SyncLog {

    public enum Status  { RUNNING, SUCCESS, ERROR }
    public enum Trigger { MANUAL, SCHEDULED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "connector_id", nullable = false)
    private PlatformConnector connector;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    private int created;
    private int updated;
    private int errors;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Trigger trigger = Trigger.MANUAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.RUNNING;

    @Column(columnDefinition = "TEXT")
    private String message;

    public long durationSeconds() {
        if (startedAt == null || finishedAt == null) return 0;
        return java.time.Duration.between(startedAt, finishedAt).getSeconds();
    }
}
