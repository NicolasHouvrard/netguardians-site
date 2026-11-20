package com.logicielpourtous.site.features.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Modele de droits de depart d'un nouveau partenaire. */
@SpringBootTest
class ModeleDroitsPartenaireServiceTest {

    private static final Set<String> INITIAL = Set.of("clients.lire", "clients.creer", "clients.entrer");

    @Autowired private ModeleDroitsPartenaireService modele;

    @AfterEach
    void restaurer() {
        modele.remplacer(INITIAL);
    }

    @Test
    void droitsRenvoieLesTroisDroitsDeDepart() {
        assertThat(modele.droits()).containsExactlyInAnyOrderElementsOf(INITIAL);
    }

    @Test
    void remplacerNeLaisseQueLesNouveauxDroits() {
        modele.remplacer(Set.of("clients.lire"));
        assertThat(modele.droits()).containsExactly("clients.lire");
    }

    @Test
    void unDroitInvalideLeveUneExceptionEtLeModeleResteInchange() {
        assertThatThrownBy(() -> modele.remplacer(Set.of("clients.lire", "Mauvais Droit")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(modele.droits()).containsExactlyInAnyOrderElementsOf(INITIAL);
    }

    @Test
    void unEnsembleVideEstRefuse() {
        assertThatThrownBy(() -> modele.remplacer(Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(modele.droits()).containsExactlyInAnyOrderElementsOf(INITIAL);
    }
}
