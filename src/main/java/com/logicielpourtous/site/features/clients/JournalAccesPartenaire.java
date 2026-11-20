package com.logicielpourtous.site.features.clients;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ecriture du journal d'acces du partenaire dans SA PROPRE transaction : la trace est conservee meme si l'appelant
 * echoue ou annule ensuite (et un echec d'ecriture n'abime pas la transaction de l'appelant).
 */
@Component
class JournalAccesPartenaire {

    static final String ENTREE = "ENTREE";
    static final String ENTREE_REFUSEE = "ENTREE_REFUSEE";

    private final JournalAccesRepository journal;

    JournalAccesPartenaire(JournalAccesRepository journal) {
        this.journal = journal;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void enregistrer(Long userId, Long clientSocieteId, String action) {
        journal.saveAndFlush(new JournalAcces(userId, clientSocieteId, action));
    }
}
