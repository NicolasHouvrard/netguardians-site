package com.logicielpourtous.site.features.request;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CommentRepository extends JpaRepository<Comment, Long> {
    void deleteByRequestId(Long requestId);

    /** Idempotence de la reception des commentaires synchronises (module {@code sync}). */
    boolean existsBySyncId(UUID syncId);

    /**
     * Commentaires d'une demande par REQUETE (module {@code sync}, figeage du payload) : contrairement a
     * {@code request.getComments()}, inclut un commentaire tout juste persiste sans avoir ete ajoute a la
     * collection en memoire d'une instance deja chargee dans le meme contexte de persistance.
     */
    java.util.List<Comment> findByRequestId(Long requestId);
}