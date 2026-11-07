package com.logicielpourtous.site.features.commun;

/**
 * Publie par {@link TenantManagementService} juste AVANT l'enregistrement d'un nouveau tenant : les
 * autres modules peuvent completer l'entite (ex. serveur d'accueil par defaut, module {@code licence})
 * sans que {@code commun} ne depende d'eux (regle de frontieres verifiee par {@code ModularityTests}).
 */
public record TenantEnCreationEvenement(Tenant tenant) {
}
