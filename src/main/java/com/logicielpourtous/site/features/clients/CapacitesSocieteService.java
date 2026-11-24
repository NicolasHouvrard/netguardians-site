package com.logicielpourtous.site.features.clients;

import java.util.Locale;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.logicielpourtous.site.features.plateforme.ControleCapacites;

/** Capacités d'une société ({@code societe_capacites}) pour la navigation : zone PARTENAIRE ou CLIENT. */
@Component
class CapacitesSocieteService implements ControleCapacites {

    private final CapaciteSocieteRepository capacites;

    CapacitesSocieteService(CapaciteSocieteRepository capacites) {
        this.capacites = capacites;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean aCapacite(Long societeId, String capacite) {
        if (societeId == null || capacite == null || capacite.isBlank()) {
            return false;
        }
        try {
            return capacites.existsBySocieteIdAndCapacite(societeId, TypeCapacite.valueOf(capacite.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
