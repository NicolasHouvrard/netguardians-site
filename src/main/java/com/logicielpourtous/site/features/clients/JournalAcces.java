package com.logicielpourtous.site.features.clients;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Une ligne du journal des entrees du partenaire dans l'espace d'un client. */
@Entity
@Table(name = "journal_acces_partenaire", schema = "public")
@Getter
@NoArgsConstructor
public class JournalAcces {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "partenaire_user_id", nullable = false)
    private Long partenaireUserId;

    @Column(name = "client_societe_id", nullable = false)
    private Long clientSocieteId;

    @Column(name = "action", nullable = false, length = 40)
    private String action;

    @Column(name = "cree_a", nullable = false)
    private Instant creeA;

    public JournalAcces(Long partenaireUserId, Long clientSocieteId, String action) {
        this.partenaireUserId = partenaireUserId;
        this.clientSocieteId = clientSocieteId;
        this.action = action;
        this.creeA = Instant.now();
    }
}
