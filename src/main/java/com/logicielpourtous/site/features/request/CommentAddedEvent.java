package com.logicielpourtous.site.features.request;

/**
 * Evenement de domaine publie par Spring Data au {@code commentRepository.save(...)}
 * d'un {@link Comment} nouvellement cree. Enregistre via {@link Comment#registerAdded()}
 * AVANT l'appel a {@code save()}.
 *
 * <p>Aucun filtrage public/prive ici : c'est au consommateur (module {@code sync}) de
 * decider quoi transmettre a qui.
 */
public record CommentAddedEvent(Comment comment) {
}
