package com.logicielpourtous.site.features.clients;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Capacite portee par une societe du registre (voir {@code V11__capacites_clients.sql}). */
@Entity
@Table(name = "societe_capacites", schema = "public")
@Getter
@Setter
public class CapaciteSociete {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "societe_id", nullable = false)
    private Long societeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "capacite", nullable = false, length = 20)
    private TypeCapacite capacite;

    @Column(name = "cree_a", nullable = false)
    private Instant creeA;

    @PrePersist
    protected void onCreate() {
        if (creeA == null) {
            creeA = Instant.now();
        }
    }
}
