package com.logicielpourtous.site.features.clients;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Rattache une societe cliente a son unique partenaire referent, et porte le partenaire par defaut du serveur. */
@Service
public class PartenaireReferentService {

    static final String ORIGINE_INSCRIPTION = "INSCRIPTION";

    private final LienClientRepository liens;
    private final CapaciteSocieteRepository capacites;
    private final String partenaireDefautConfigure;

    public PartenaireReferentService(LienClientRepository liens, CapaciteSocieteRepository capacites,
            @Value("${app.clients.partenaire-defaut-societe-id:}") String partenaireDefautConfigure) {
        this.liens = liens;
        this.capacites = capacites;
        this.partenaireDefautConfigure = partenaireDefautConfigure;
    }

    /**
     * Rattache le client au partenaire (origine : {@code PARTENAIRE} ou {@code INSCRIPTION}) et lui accorde la
     * capacite CLIENT si absente. Toutes les verifications precedent la premiere ecriture.
     */
    @Transactional
    public LienClient rattacher(Long clientSocieteId, Long partenaireSocieteId, String origine) {
        if (clientSocieteId.equals(partenaireSocieteId)) {
            throw new IllegalArgumentException("Un partenaire ne peut pas etre son propre referent.");
        }
        if (!capacites.existsBySocieteIdAndCapacite(partenaireSocieteId, TypeCapacite.PARTENAIRE)) {
            throw new IllegalArgumentException("La societe " + partenaireSocieteId + " n'a pas la capacite PARTENAIRE.");
        }
        if (liens.findByClientSocieteId(clientSocieteId).isPresent()) {
            throw new IllegalStateException("Le client " + clientSocieteId
                    + " est deja rattache ; un changement de partenaire suit la procedure dediee.");
        }
        if (!capacites.existsBySocieteIdAndCapacite(clientSocieteId, TypeCapacite.CLIENT)) {
            CapaciteSociete capacite = new CapaciteSociete();
            capacite.setSocieteId(clientSocieteId);
            capacite.setCapacite(TypeCapacite.CLIENT);
            capacites.save(capacite);
        }
        LienClient lien = new LienClient();
        lien.setClientSocieteId(clientSocieteId);
        lien.setPartenaireSocieteId(partenaireSocieteId);
        lien.setOrigine(origine);
        return liens.save(lien);
    }

    /** Societe partenaire par defaut ; leve {@link PartenaireDefautAbsentException} si absente ou non partenaire. */
    @Transactional(readOnly = true)
    public Long partenaireDefaut() {
        String brut = partenaireDefautConfigure == null ? "" : partenaireDefautConfigure.trim();
        if (brut.isEmpty()) {
            throw new PartenaireDefautAbsentException("Aucun partenaire par defaut n'est configure"
                    + " (app.clients.partenaire-defaut-societe-id).");
        }
        Long id;
        try {
            id = Long.valueOf(brut);
        } catch (NumberFormatException e) {
            throw new PartenaireDefautAbsentException("Le partenaire par defaut configure n'est pas un identifiant : " + brut);
        }
        if (!capacites.existsBySocieteIdAndCapacite(id, TypeCapacite.PARTENAIRE)) {
            throw new PartenaireDefautAbsentException("La societe " + id
                    + " designee comme partenaire par defaut n'a pas la capacite PARTENAIRE.");
        }
        return id;
    }

    /** Rattache le client au partenaire par defaut (origine {@code INSCRIPTION}) ; aucun lien si pas de defaut. */
    @Transactional
    public LienClient rattacherAuDefaut(Long clientSocieteId) {
        return rattacher(clientSocieteId, partenaireDefaut(), ORIGINE_INSCRIPTION);
    }
}
