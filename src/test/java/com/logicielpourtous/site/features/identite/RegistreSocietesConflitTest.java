package com.logicielpourtous.site.features.identite;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Traduction des erreurs de la base a l'enregistrement : seule une violation d'unicite (SQLState 23505)
 * devient un conflit d'enregistrement ; toute autre violation d'integrite est une erreur de programmation et
 * doit remonter telle quelle.
 */
class RegistreSocietesConflitTest {

    private RegistreSocietes registreOuLaBaseRefuse(String sqlState) {
        SocieteIdentiteRepository repository = mock(SocieteIdentiteRepository.class);
        when(repository.findBySiren(any())).thenReturn(Optional.empty());
        when(repository.findByTva(any())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenThrow(
                new DataIntegrityViolationException("refus", new SQLException("refus", sqlState)));
        return new RegistreSocietes(repository);
    }

    @Test
    void uneViolationDUniciteDeLaBaseDevientUnConflitDEnregistrement() {
        IdentiteSociete identite = IdentiteSociete.depuis("404833048", null, "FR83404833048");

        assertThatThrownBy(() -> registreOuLaBaseRefuse("23505").enregistrer(TypeSociete.CLIENT, identite, null))
                .isInstanceOf(EnregistrementConcurrentException.class);
    }

    @Test
    void uneAutreViolationDIntegriteRemonteTelleQuelle() {
        IdentiteSociete identite = IdentiteSociete.depuis("404833048", null, "FR83404833048");

        // 23502 = colonne non nulle ; 22001 = valeur trop longue
        assertThatThrownBy(() -> registreOuLaBaseRefuse("23502").enregistrer(TypeSociete.CLIENT, identite, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> registreOuLaBaseRefuse("22001").enregistrer(TypeSociete.CLIENT, identite, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
