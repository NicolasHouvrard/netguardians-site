package com.logicielpourtous.site.features.clients;

import org.springframework.stereotype.Component;

import com.logicielpourtous.site.features.identite.AttributionCapacites;

/** Adaptateur du port {@link AttributionCapacites} : ecrit la ligne de capacite CLIENT (sans doublon). */
@Component
class AttributionCapacitesClients implements AttributionCapacites {

    private final CapaciteSocieteRepository capacites;

    AttributionCapacitesClients(CapaciteSocieteRepository capacites) {
        this.capacites = capacites;
    }

    @Override
    public void accorderClient(Long societeId) {
        if (!capacites.existsBySocieteIdAndCapacite(societeId, TypeCapacite.CLIENT)) {
            CapaciteSociete ligne = new CapaciteSociete();
            ligne.setSocieteId(societeId);
            ligne.setCapacite(TypeCapacite.CLIENT);
            capacites.save(ligne);
        }
    }
}
