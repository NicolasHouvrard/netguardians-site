package com.logicielpourtous.site.features.identite;

/**
 * Port sortant : attribue la capacite CLIENT a une societe du registre. Defini ici pour que le module
 * {@code identite} ne depende pas du module {@code clients} ; implemente par ce dernier.
 */
public interface AttributionCapacites {

    void accorderClient(Long societeId);
}
