package com.logicielpourtous.site.features.clients;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.logicielpourtous.site.sdk.ManifestePlugin;

/** Modele de droits de depart d'un nouveau partenaire. */
@Service
public class ModeleDroitsPartenaireService {

    private final ModeleDroitsPartenaireRepository repository;

    ModeleDroitsPartenaireService(ModeleDroitsPartenaireRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Set<String> droits() {
        return repository.findAll().stream().map(ModeleDroitsPartenaire::getDroit).collect(Collectors.toSet());
    }

    /** Remplace tout le modele ; rien n'est ecrit si un droit est invalide ou si l'ensemble est vide. */
    @Transactional
    public void remplacer(Set<String> droits) {
        if (droits == null || droits.isEmpty()) {
            throw new IllegalArgumentException("le modele de droits ne peut pas etre vide");
        }
        for (String droit : droits) {
            if (droit == null || !ManifestePlugin.DROIT_VALIDE.matcher(droit).matches()) {
                throw new IllegalArgumentException("droit invalide : '" + droit + "'");
            }
        }
        repository.deleteAllInBatch();
        repository.saveAllAndFlush(droits.stream().map(ModeleDroitsPartenaire::new).toList());
    }
}
