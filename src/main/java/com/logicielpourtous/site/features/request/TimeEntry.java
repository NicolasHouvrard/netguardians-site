package com.logicielpourtous.site.features.request;

import com.logicielpourtous.site.features.user.User;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "time_entries")
public class TimeEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Integer durationMinutes;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private LocalDate date;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "request_id", nullable = false)
    private Request request;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Integer getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(Integer durationMinutes) { this.durationMinutes = durationMinutes; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }

    public LocalTime getStartTime() { return startTime; }
    public void setStartTime(LocalTime startTime) { this.startTime = startTime; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public User getAuthor() { return author; }
    public void setAuthor(User author) { this.author = author; }

    public Request getRequest() { return request; }
    public void setRequest(Request request) { this.request = request; }

    /** Formats durationMinutes as "1h30" or "45min" */
    public String getFormattedDuration() {
        if (durationMinutes == null || durationMinutes == 0) return "0min";
        int h = durationMinutes / 60;
        int m = durationMinutes % 60;
        if (h == 0) return m + "min";
        if (m == 0) return h + "h";
        return h + "h" + String.format("%02d", m);
    }
}
