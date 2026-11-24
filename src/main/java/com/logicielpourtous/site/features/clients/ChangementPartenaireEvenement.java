package com.logicielpourtous.site.features.clients;

/** Publie quand un changement de partenaire est applique : l'ancien partenaire doit en etre informe. */
public record ChangementPartenaireEvenement(Long clientSocieteId, Long ancienPartenaireId, Long nouveauPartenaireId) {
}
