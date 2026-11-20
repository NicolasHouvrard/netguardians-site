package com.logicielpourtous.site.features.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import com.logicielpourtous.site.features.plateforme.ContratRegistre;
import com.logicielpourtous.site.sdk.AnnuaireClientsContrat;
import com.logicielpourtous.site.sdk.IdentiteContrat;
import com.logicielpourtous.site.sdk.IdentiteContrat.IdentiteCourante;

/** Le noyau publie ses contrats au registre : un bean d'un contexte enfant (plugin) peut les injecter. */
class PublicationContratsCoeurTest {

    @Test
    void unBeanDUnContexteEnfantRecoitLesContratsDuNoyau() {
        IdentiteContrat identite = mock(IdentiteContrat.class);
        when(identite.courante()).thenReturn(new IdentiteCourante(7L, Set.of("ROLE_X"), 3L, 4L, "MASTER"));
        AnnuaireClientsContrat annuaire = mock(AnnuaireClientsContrat.class);
        when(annuaire.clientsDuPartenaire(3L)).thenReturn(List.of());

        ContratRegistre registre = new ContratRegistre();
        new PublicationContratsCoeur(registre, identite, annuaire);

        GenericApplicationContext parent = new GenericApplicationContext();
        parent.registerBean(ContratRegistre.class, () -> registre);
        parent.refresh();
        GenericApplicationContext enfant = new GenericApplicationContext();
        enfant.setParent(parent);
        enfant.registerBean("identite", IdentiteContrat.class, () -> enfant.getBean(ContratRegistre.class).obtenir(IdentiteContrat.class));
        enfant.registerBean("annuaire", AnnuaireClientsContrat.class,
                () -> enfant.getBean(ContratRegistre.class).obtenir(AnnuaireClientsContrat.class));
        enfant.registerBean(ConsommateurDeTest.class);
        enfant.refresh();

        ConsommateurDeTest conso = enfant.getBean(ConsommateurDeTest.class);
        assertThat(conso.identite.courante().userId()).isEqualTo(7L);
        assertThat(conso.annuaire.clientsDuPartenaire(3L)).isEmpty();
    }

    public static class ConsommateurDeTest {

        public final IdentiteContrat identite;
        public final AnnuaireClientsContrat annuaire;

        public ConsommateurDeTest(IdentiteContrat identite, AnnuaireClientsContrat annuaire) {
            this.identite = identite;
            this.annuaire = annuaire;
        }
    }
}
