package com.logicielpourtous.site.features.clients;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Ecriture du journal des actions du maitre dans SA PROPRE transaction (la trace survit a l'echec de l'appelant). */
@Component
public class JournalActionsMaitre {

    public static final String PARTENAIRE_CREE = "PARTENAIRE_CREE";
    public static final String CLIENT_CREE_POUR_PARTENAIRE = "CLIENT_CREE_POUR_PARTENAIRE";
    public static final String MODELE_DROITS_MODIFIE = "MODELE_DROITS_MODIFIE";

    private final JournalActionMaitreRepository journal;

    JournalActionsMaitre(JournalActionMaitreRepository journal) {
        this.journal = journal;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enregistrer(Long adminUserId, String action, Long partenaireSocieteId, Long objetSocieteId) {
        journal.saveAndFlush(new JournalActionMaitre(adminUserId, action, partenaireSocieteId, objetSocieteId));
    }
}
