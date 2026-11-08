package com.logicielpourtous.site.features.identite;

import java.util.List;

/**
 * Societes qui partagent un meme identifiant dans les tables existantes. {@code nature} vaut {@code "SIREN"}
 * ou {@code "TVA"} ; {@code cle} est l'identifiant normalise commun.
 */
public record GroupeDoublon(String nature, String cle, List<LigneSource> lignes) {

    /** Une fiche d'une table existante ({@code customers}, {@code tenants} ou {@code partner_esns}). */
    public record LigneSource(String table, Long id, String nom) {
    }
}
