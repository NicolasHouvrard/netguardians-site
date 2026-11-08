package com.logicielpourtous.site.features.identite;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registre unique des societes de la plateforme : une societe n'y figure qu'une fois, quel que soit son type.
 * Le controle applicatif est double d'index uniques en base (filet de securite en cas d'acces concurrents).
 *
 * <p><b>Contrat transactionnel pour l'appelant</b> : {@link SocieteDejaConnueException} est levee AVANT toute
 * ecriture ; elle ne marque donc PAS la transaction de l'appelant comme annulee, et il peut l'attraper pour
 * afficher son message neutre et enregistrer une notification dans la meme transaction.
 * {@link EnregistrementConcurrentException} signale au contraire que la BASE a refuse l'ecriture : sa
 * transaction est deja avortee, l'appelant doit la recommencer.
 */
@Service
@Transactional
public class RegistreSocietes {

    /** SQLState PostgreSQL d'une violation de contrainte d'unicite. */
    private static final String VIOLATION_UNICITE = "23505";

    private final SocieteIdentiteRepository repository;

    public RegistreSocietes(SocieteIdentiteRepository repository) {
        this.repository = repository;
    }

    /** Retrouve une societe par son SIREN s'il existe, sinon par sa TVA. */
    @Transactional(readOnly = true)
    public Optional<SocieteIdentite> trouver(IdentiteSociete identite) {
        Optional<SocieteIdentite> parSiren = identite.siren() == null
                ? Optional.empty()
                : repository.findBySiren(identite.siren());
        return parSiren.isPresent() ? parSiren : repository.findByTva(identite.tva());
    }

    /**
     * @param proprietaireTenantId tenant du partenaire proprietaire ; {@code null} = le Maitre
     * @throws SocieteDejaConnueException       si la societe est deja enregistree (avant toute ecriture)
     * @throws EnregistrementConcurrentException si la base refuse l'ecriture pour cause d'unicite
     * @throws IllegalArgumentException         si une societe etrangere n'est pas declaree fournisseur
     */
    @Transactional(noRollbackFor = SocieteDejaConnueException.class)
    public SocieteIdentite enregistrer(TypeSociete type, IdentiteSociete identite, Long proprietaireTenantId) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(identite, "identite");
        if (identite.etrangere() && type != TypeSociete.FOURNISSEUR) {
            throw new IllegalArgumentException("Une société étrangère ne peut être qu'un fournisseur.");
        }
        Optional<SocieteIdentite> existante = trouver(identite);
        if (existante.isPresent()) {
            throw new SocieteDejaConnueException(existante.get());
        }
        SocieteIdentite nouvelle = new SocieteIdentite();
        nouvelle.setSiren(identite.siren());
        nouvelle.setTva(identite.tva());
        nouvelle.setType(type);
        nouvelle.setProprietaireTenantId(proprietaireTenantId);
        try {
            return repository.saveAndFlush(nouvelle);
        } catch (DataIntegrityViolationException e) {
            // Seule une violation d'UNICITE est un conflit ; toute autre violation (colonne non nulle,
            // valeur trop longue...) est une erreur de programmation qui doit remonter telle quelle.
            if (estViolationDUnicite(e)) {
                throw new EnregistrementConcurrentException(e);
            }
            throw e;
        }
    }

    private static boolean estViolationDUnicite(Throwable erreur) {
        for (Throwable t = erreur; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && VIOLATION_UNICITE.equals(sql.getSQLState())) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
