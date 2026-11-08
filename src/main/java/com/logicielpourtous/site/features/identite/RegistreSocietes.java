package com.logicielpourtous.site.features.identite;

import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registre unique des societes de la plateforme : une societe n'y figure qu'une fois, quel que soit son type.
 * Le controle applicatif est double d'index uniques en base (filet de securite en cas d'acces concurrents).
 */
@Service
@Transactional
public class RegistreSocietes {

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
     * @throws SocieteDejaConnueException si la societe est deja enregistree
     * @throws IllegalArgumentException   si une societe etrangere n'est pas declaree fournisseur
     */
    public SocieteIdentite enregistrer(TypeSociete type, IdentiteSociete identite, Long proprietaireTenantId) {
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
            throw new SocieteDejaConnueException(null);
        }
    }
}
