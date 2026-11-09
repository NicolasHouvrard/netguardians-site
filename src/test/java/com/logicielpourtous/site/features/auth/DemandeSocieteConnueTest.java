package com.logicielpourtous.site.features.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.logicielpourtous.site.features.identite.IdentiteSociete;
import com.logicielpourtous.site.features.identite.RegistreSocietes;
import com.logicielpourtous.site.features.identite.SocieteIdentite;
import com.logicielpourtous.site.features.identite.TypeSociete;
import com.logicielpourtous.site.features.registration.SubscriptionRequest;
import com.logicielpourtous.site.features.registration.SubscriptionRequestRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/** Donnees de l'inscription autonome : demande « societe connue » et numeros sur la demande d'ESN. */
@SpringBootTest
@Transactional
class DemandeSocieteConnueTest {

    @Autowired private DemandeSocieteConnueRepository demandes;
    @Autowired private SubscriptionRequestRepository subscriptionRequests;
    @Autowired private RegistreSocietes registre;
    @PersistenceContext private EntityManager em;

    @Test
    void demandeSocieteConnue_estRelueAvecDateEtNonTraitee() {
        SocieteIdentite societe = registre.enregistrer(TypeSociete.CLIENT,
                IdentiteSociete.depuis("552032534", null, "FR27552032534"), null);
        DemandeSocieteConnue d = new DemandeSocieteConnue();
        d.setSocieteIdentiteId(societe.getId());
        d.setEmailDemandeur("alice@example.com");
        d.setNomDemandeur("Alice");
        Long id = demandes.save(d).getId();
        em.flush();
        em.clear();

        DemandeSocieteConnue relue = demandes.findById(id).orElseThrow();

        assertThat(relue.getSocieteIdentiteId()).isEqualTo(societe.getId());
        assertThat(relue.getEmailDemandeur()).isEqualTo("alice@example.com");
        assertThat(relue.getNomDemandeur()).isEqualTo("Alice");
        assertThat(relue.getCreeA()).isNotNull();
        assertThat(relue.isTraitee()).isFalse();
    }

    @Test
    void demandeEsn_conserveSirenEtTva() {
        SubscriptionRequest r = new SubscriptionRequest();
        r.setCompanyName("ESN Test");
        r.setContactName("Bob");
        r.setEmail("bob@example.com");
        r.setSiren("552032534");
        r.setVatNumber("FR27552032534");
        Long id = subscriptionRequests.save(r).getId();
        em.flush();
        em.clear();

        SubscriptionRequest relue = subscriptionRequests.findById(id).orElseThrow();

        assertThat(relue.getSiren()).isEqualTo("552032534");
        assertThat(relue.getVatNumber()).isEqualTo("FR27552032534");
    }
}
