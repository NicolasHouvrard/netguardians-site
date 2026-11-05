package com.logicielpourtous.site.features.request;

/**
 * Evenement de domaine publie par Spring Data au {@code requestRepository.save(...)}
 * qui suit une mutation significative d'une {@link Request} (creation, statut,
 * assignation equipe/technicien/type...).
 *
 * <p>Enregistre via {@link Request#registerChange()} AVANT l'appel a {@code save()} :
 * jamais depuis un callback JPA ({@code @PrePersist}/{@code @PreUpdate}), dont le timing
 * par rapport a la publication des evenements de Spring Data n'est pas garanti.
 */
public record RequestChangedEvent(Request request) {
}
