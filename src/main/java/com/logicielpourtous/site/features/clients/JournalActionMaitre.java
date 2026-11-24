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

/** Une ligne du journal des actions du maitre. */
@Entity
@Table(name = "journal_actions_maitre", schema = "public")
@Getter
@NoArgsConstructor
public class JournalActionMaitre {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "admin_user_id", nullable = false)
    private Long adminUserId;

    @Column(name = "action", nullable = false, length = 40)
    private String action;

    @Column(name = "partenaire_societe_id")
    private Long partenaireSocieteId;

    @Column(name = "objet_societe_id")
    private Long objetSocieteId;

    @Column(name = "cree_a", nullable = false)
    private Instant creeA;

    public JournalActionMaitre(Long adminUserId, String action, Long partenaireSocieteId, Long objetSocieteId) {
        this.adminUserId = adminUserId;
        this.action = action;
        this.partenaireSocieteId = partenaireSocieteId;
        this.objetSocieteId = objetSocieteId;
        this.creeA = Instant.now();
    }
}
