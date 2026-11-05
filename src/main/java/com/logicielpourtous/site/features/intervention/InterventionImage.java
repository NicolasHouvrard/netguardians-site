package com.logicielpourtous.site.features.intervention;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "intervention_images")
@Getter @Setter @NoArgsConstructor
public class InterventionImage {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "intervention_id", nullable = false)
    private Intervention intervention;

    @Column(nullable = false)
    private String filename;

    @Column(name = "original_name")
    private String originalName;

    @Column(name = "uploaded_at")
    private LocalDateTime uploadedAt;

    @PrePersist
    protected void onCreate() { this.uploadedAt = LocalDateTime.now(); }
}
