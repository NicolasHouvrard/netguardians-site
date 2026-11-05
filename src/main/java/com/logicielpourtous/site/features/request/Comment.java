package com.logicielpourtous.site.features.request;

import com.logicielpourtous.site.features.user.User;
import jakarta.persistence.*;
import org.springframework.data.domain.AbstractAggregateRoot;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Commentaire d'une {@link Request}.
 *
 * <p>Etend {@link AbstractAggregateRoot} (independamment de {@code Syncable} : pas de
 * {@code syncId} propre, il est rattache au {@code syncId} de sa {@link Request}) afin de
 * publier un {@link CommentAddedEvent} au {@code commentRepository.save(...)} via
 * {@link #registerAdded()}.
 */
@Entity
@Table(name = "comments")
public class Comment extends AbstractAggregateRoot<Comment> {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Cle de synchronisation stable du commentaire (meme patron que {@code Syncable.syncId}) : generee a
     * la creation si absente, jamais modifiee. Permet a la reception d'un commentaire synchronise de savoir
     * s'il existe deja localement (un meme commentaire peut arriver plusieurs fois, pulls repetes).
     * Lignes anterieures : backfillees par {@code sync-id-backfill-migration.sql}.
     */
    @Column(name = "sync_id", updatable = false)
    private UUID syncId;

    @Lob // Indique que le champ peut contenir un texte long
    @Column(nullable = false)
    private String content;

    // true = public, false = privé (réservé au support)
    private boolean isPublic = true;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", nullable = true)
    private User author;

    /** Rempli quand le commentaire provient d'un email entrant (pas d'utilisateur) */
    @Column(name = "source_email")
    private String sourceEmail;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "request_id", nullable = false)
    private Request request;

    @PrePersist
    protected void onCreate() {
        // Respecte une date deja fixee (commentaire recu par synchronisation : date d'origine).
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (syncId == null) {
            syncId = UUID.randomUUID();
        }
    }

    // --- Getters and Setters ---

    public Long getId() {
        return id;
    }

    public UUID getSyncId() {
        return syncId;
    }

    /**
     * Reserve au module {@code sync} : copie locale d'un commentaire RECU d'un autre serveur, qui garde son
     * {@code syncId} d'origine. Uniquement sur une instance neuve.
     */
    public void adopterSyncIdRecu(UUID syncIdRecu) {
        if (syncId != null || id != null) {
            throw new IllegalStateException("syncId deja attribue ou commentaire deja persiste : syncId immuable");
        }
        this.syncId = Objects.requireNonNull(syncIdRecu, "syncIdRecu");
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public boolean isPublic() {
        return isPublic;
    }

    public void setPublic(boolean aPublic) {
        isPublic = aPublic;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public User getAuthor() {
        return author;
    }

    public void setAuthor(User author) {
        this.author = author;
    }

    public String getSourceEmail() { return sourceEmail; }
    public void setSourceEmail(String sourceEmail) { this.sourceEmail = sourceEmail; }

    public Request getRequest() {
        return request;
    }

    public void setRequest(Request request) {
        this.request = request;
    }

    /**
     * Enregistre un {@link CommentAddedEvent} publie par Spring Data au prochain
     * {@code commentRepository.save(this)}. A appeler juste AVANT ce {@code save()}, sur un
     * commentaire nouvellement cree (pas depuis un callback JPA). Ne PAS appeler lors de
     * l'application d'un commentaire recu par synchronisation (prevention d'echo, Task 4).
     */
    public void registerAdded() {
        registerEvent(new CommentAddedEvent(this));
    }
}
