package com.logicielpourtous.site.features.licence;

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

/**
 * Compte de licence (MP) : identifiants et abonnement permettant, dans une future tache de
 * ce plan, a un serveur secondaire de s'auto-enroler auprès de ce maitre.
 *
 * <p>Entite purement locale au module {@code licence}, geree uniquement depuis l'ecran
 * d'administration {@code /intra/licence/comptes}, lui-meme disponible seulement si cette
 * instance est {@code MASTER} ({@link com.logicielpourtous.site.features.commun.ServerRoleProperties}).
 */
@Entity
@Table(name = "comptes_licence")
@Getter
@Setter
public class CompteLicence {

    /** Une seule valeur pour l'instant : les abonnements limites viendront dans une tache ulterieure. */
    public enum TypeAbonnement {
        ILLIMITE
    }

    public enum Frequence {
        MENSUEL,
        ANNUEL
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String login;

    /** Hash (BCrypt, via le {@code PasswordEncoder} partage de {@code SecurityConfig}) — jamais le mot de passe en clair. */
    private String motDePasseHash;

    @Enumerated(EnumType.STRING)
    private TypeAbonnement typeAbonnement;

    @Enumerated(EnumType.STRING)
    private Frequence frequence;

    private int nombreServeursAutorises;

    private boolean actif = true;

    private Instant creeA;

    @PrePersist
    protected void onCreate() {
        if (creeA == null) {
            creeA = Instant.now();
        }
    }
}
