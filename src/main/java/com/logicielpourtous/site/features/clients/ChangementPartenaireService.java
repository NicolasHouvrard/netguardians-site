package com.logicielpourtous.site.features.clients;

import java.time.Clock;
import java.time.Year;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changement de partenaire referent : un seul changement applique par annee civile et par client ; au-dela, une
 * demande {@code EN_ATTENTE} est creee et le lien n'est modifie que si l'admin accepte (via {@link #trancher}).
 * L'autorisation (admin) est a la charge de l'appelant.
 */
@Service
public class ChangementPartenaireService {

    static final String EN_ATTENTE = "EN_ATTENTE";
    static final String ACCEPTEE = "ACCEPTEE";
    static final String REFUSEE = "REFUSEE";
    static final int CHANGEMENTS_MAX_PAR_AN = 1;

    /** {@code applique} : le lien a ete modifie ; sinon {@code demandeId} designe la demande a valider. */
    public record ResultatChangement(boolean applique, Long demandeId) {
    }

    private final LienClientRepository liens;
    private final CapaciteSocieteRepository capacites;
    private final DemandeChangementPartenaireRepository demandes;
    private final ApplicationEventPublisher evenements;
    private final Clock horloge;

    public ChangementPartenaireService(LienClientRepository liens, CapaciteSocieteRepository capacites,
            DemandeChangementPartenaireRepository demandes, ApplicationEventPublisher evenements, Clock horloge) {
        this.liens = liens;
        this.capacites = capacites;
        this.demandes = demandes;
        this.evenements = evenements;
        this.horloge = horloge;
    }

    @Transactional
    public ResultatChangement demander(Long clientSocieteId, Long nouveauPartenaireSocieteId, String motif) {
        if (clientSocieteId == null || nouveauPartenaireSocieteId == null) {
            throw new IllegalArgumentException("Le client et le nouveau partenaire doivent etre renseignes.");
        }
        LienClient lien = liens.findWithLockByClientSocieteId(clientSocieteId).orElseThrow(
                () -> new IllegalStateException("Le client " + clientSocieteId
                        + " n'a pas de partenaire : il n'y a rien a changer."));
        if (demandes.existsByClientSocieteIdAndStatut(clientSocieteId, EN_ATTENTE)) {
            throw new IllegalStateException("Le client " + clientSocieteId
                    + " a deja une demande de changement en attente de validation.");
        }
        verifierNouveauPartenaire(lien, nouveauPartenaireSocieteId);

        if (changementsDeLAnnee(lien) < CHANGEMENTS_MAX_PAR_AN) {
            appliquer(lien, nouveauPartenaireSocieteId);
            return new ResultatChangement(true, null);
        }
        DemandeChangementPartenaire demande = new DemandeChangementPartenaire();
        demande.setClientSocieteId(clientSocieteId);
        demande.setNouveauPartenaireSocieteId(nouveauPartenaireSocieteId);
        demande.setMotif(motif);
        demande.setStatut(EN_ATTENTE);
        return new ResultatChangement(false, demandes.save(demande).getId());
    }

    /** Decision de l'admin sur une demande en attente ; accepter applique le changement. */
    @Transactional
    public void trancher(Long demandeId, boolean accepter) {
        if (demandeId == null) {
            throw new IllegalArgumentException("La demande doit etre renseignee.");
        }
        DemandeChangementPartenaire demande = demandes.findWithLockById(demandeId)
                .orElseThrow(() -> new IllegalArgumentException("Demande inconnue : " + demandeId));
        if (!EN_ATTENTE.equals(demande.getStatut())) {
            throw new IllegalStateException("La demande " + demandeId + " est deja traitee (" + demande.getStatut() + ").");
        }
        if (accepter) {
            LienClient lien = liens.findWithLockByClientSocieteId(demande.getClientSocieteId()).orElseThrow(
                    () -> new IllegalStateException("Le client " + demande.getClientSocieteId() + " n'a plus de partenaire."));
            verifierNouveauPartenaire(lien, demande.getNouveauPartenaireSocieteId());
            appliquer(lien, demande.getNouveauPartenaireSocieteId());
        }
        demande.setStatut(accepter ? ACCEPTEE : REFUSEE);
        demandes.save(demande);
    }

    private void verifierNouveauPartenaire(LienClient lien, Long nouveauPartenaireId) {
        if (nouveauPartenaireId.equals(lien.getPartenaireSocieteId())) {
            throw new IllegalArgumentException("Le client est deja rattache a ce partenaire.");
        }
        if (nouveauPartenaireId.equals(lien.getClientSocieteId())) {
            throw new IllegalArgumentException("Un partenaire ne peut pas etre son propre referent.");
        }
        if (!capacites.existsBySocieteIdAndCapacite(nouveauPartenaireId, TypeCapacite.PARTENAIRE)) {
            throw new IllegalArgumentException("La societe " + nouveauPartenaireId + " n'a pas la capacite PARTENAIRE.");
        }
    }

    private int anneeCourante() {
        return Year.now(horloge).getValue();
    }

    /** Compteur de l'annee civile courante : 0 si l'annee de reference est une autre annee. */
    private int changementsDeLAnnee(LienClient lien) {
        Integer reference = lien.getAnneeReference();
        return reference != null && reference == anneeCourante() ? lien.getChangementsAnneeCourante() : 0;
    }

    private void appliquer(LienClient lien, Long nouveauPartenaireId) {
        Long ancien = lien.getPartenaireSocieteId();
        lien.setChangementsAnneeCourante(changementsDeLAnnee(lien) + 1);
        lien.setAnneeReference(anneeCourante());
        lien.setPartenaireSocieteId(nouveauPartenaireId);
        liens.save(lien);
        evenements.publishEvent(new ChangementPartenaireEvenement(lien.getClientSocieteId(), ancien, nouveauPartenaireId));
    }
}
