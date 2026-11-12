package com.logicielpourtous.site.features.plateforme;

/** Résultat d'une migration Flyway de plugin : nombre de migrations appliquées et version du schéma obtenue. */
public record ResultatMigration(int appliquees, String versionSchema) {
}
