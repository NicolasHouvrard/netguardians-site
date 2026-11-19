package com.logicielpourtous.site.features.clients;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.logicielpourtous.site.sdk.AnnuaireClientsContrat.PorteeAcces;

/**
 * Entree d'un partenaire dans l'espace d'un de SES clients, en tant que manager, avec trace. Echec ferme : sauf
 * toutes les conditions reunies, {@link AccesRefuseException} (message identique pour toute cause : droit absent,
 * client inconnu, client d'un autre partenaire). Le client lui-meme n'obtient rien par ici : la societe de reference
 * est celle du PARTENAIRE, qui doit etre le partenaire rattache au client.
 *
 * <p>Ce service n'est pas transactionnel : le journal s'ecrit dans sa propre transaction. Un refus est journalise
 * sans jamais pouvoir casser le refus ; un succes n'est rendu que si sa trace est ecrite.</p>
 */
@Service
class AccesEspaceClientService {

    static final String DROIT_ENTRER = "clients.entrer";

    private static final Logger log = LoggerFactory.getLogger(AccesEspaceClientService.class);

    private final DroitsService droits;
    private final LienClientRepository liens;
    private final CapaciteSocieteRepository capacites;
    private final JournalAccesPartenaire journal;

    AccesEspaceClientService(DroitsService droits, LienClientRepository liens, CapaciteSocieteRepository capacites,
            JournalAccesPartenaire journal) {
        this.droits = droits;
        this.liens = liens;
        this.capacites = capacites;
        this.journal = journal;
    }

    PorteeAcces entrer(Long userId, Long partenaireSocieteId, Long clientSocieteId) {
        if (userId == null || partenaireSocieteId == null || clientSocieteId == null) {
            throw new AccesRefuseException();
        }
        if (!autorise(userId, partenaireSocieteId, clientSocieteId)) {
            journalerRefus(userId, clientSocieteId);
            throw new AccesRefuseException();
        }
        // Succes : la trace doit exister (REQUIRES_NEW), sinon pas d'entree (l'erreur remonte).
        journal.enregistrer(userId, clientSocieteId, JournalAccesPartenaire.ENTREE);
        return new PorteeAcces(clientSocieteId, droits.droitsEffectifsDuManager(clientSocieteId));
    }

    private boolean autorise(Long userId, Long partenaireSocieteId, Long clientSocieteId) {
        if (!droits.autorise(userId, partenaireSocieteId, DROIT_ENTRER)) {
            return false;
        }
        boolean referent = liens.findByClientSocieteId(clientSocieteId)
            .map(l -> partenaireSocieteId.equals(l.getPartenaireSocieteId()))
            .orElse(false);
        return referent && capacites.existsBySocieteIdAndCapacite(clientSocieteId, TypeCapacite.CLIENT);
    }

    private void journalerRefus(Long userId, Long clientSocieteId) {
        try {
            journal.enregistrer(userId, clientSocieteId, JournalAccesPartenaire.ENTREE_REFUSEE);
        }
        catch (RuntimeException e) {
            log.warn("Journal d'une entree refusee non ecrit : {}", e.getClass().getSimpleName());
        }
    }
}
