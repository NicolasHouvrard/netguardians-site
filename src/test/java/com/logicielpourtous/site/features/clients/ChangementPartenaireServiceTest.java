package com.logicielpourtous.site.features.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Changement de partenaire : un par annee civile, au-dela validation de l'admin. */
@SpringBootTest
@Transactional
class ChangementPartenaireServiceTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private CapaciteSocieteRepository capacites;
    @Autowired private LienClientRepository liens;
    @Autowired private DemandeChangementPartenaireRepository demandes;

    private final List<Object> evenements = new ArrayList<>();
    private Long p1;
    private Long p2;
    private Long p3;
    private Long client;
    private Long sansCapacite;

    @BeforeEach
    void preparer() {
        p1 = societe("CP1", "FRCP1");
        p2 = societe("CP2", "FRCP2");
        p3 = societe("CP3", "FRCP3");
        client = societe("CP4", "FRCP4");
        sansCapacite = societe("CP5", "FRCP5");
        for (Long p : List.of(p1, p2, p3)) {
            CapaciteSociete c = new CapaciteSociete();
            c.setSocieteId(p);
            c.setCapacite(TypeCapacite.PARTENAIRE);
            capacites.save(c);
        }
        evenements.clear();
        new PartenaireReferentService(liens, capacites, "").rattacher(client, p1, "PARTENAIRE");
    }

    private ChangementPartenaireService service(int annee) {
        Clock horloge = Clock.fixed(Instant.parse(annee + "-06-15T10:00:00Z"), ZoneOffset.UTC);
        return new ChangementPartenaireService(liens, capacites, demandes, evenements::add, horloge);
    }

    private Long societe(String siren, String tva) {
        return jdbc.queryForObject("INSERT INTO societes_identites (siren, tva, type) VALUES (?, ?, 'CLIENT') RETURNING id",
                Long.class, siren, tva);
    }

    private Long partenaireDuClient() {
        return liens.findByClientSocieteId(client).orElseThrow().getPartenaireSocieteId();
    }

    @Test
    void premierChangementDeLAnneeEstAppliqueEtSignale() {
        var res = service(2026).demander(client, p2, "demenagement");

        assertThat(res.applique()).isTrue();
        assertThat(res.demandeId()).isNull();
        assertThat(partenaireDuClient()).isEqualTo(p2);
        assertThat(evenements).containsExactly(new ChangementPartenaireEvenement(client, p1, p2));
        LienClient lien = liens.findByClientSocieteId(client).orElseThrow();
        assertThat(lien.getChangementsAnneeCourante()).isEqualTo(1);
        assertThat(lien.getAnneeReference()).isEqualTo(2026);
    }

    @Test
    void deuxiemeChangementLaMemeAnneeCreeUneDemandeSansToucherLeLien() {
        ChangementPartenaireService s = service(2026);
        s.demander(client, p2, null);
        evenements.clear();

        var res = s.demander(client, p3, "encore");

        assertThat(res.applique()).isFalse();
        assertThat(res.demandeId()).isNotNull();
        assertThat(partenaireDuClient()).isEqualTo(p2);
        assertThat(evenements).isEmpty();
        DemandeChangementPartenaire d = demandes.findById(res.demandeId()).orElseThrow();
        assertThat(d.getStatut()).isEqualTo("EN_ATTENTE");
        assertThat(d.getNouveauPartenaireSocieteId()).isEqualTo(p3);
        assertThat(d.getMotif()).isEqualTo("encore");
    }

    @Test
    void demandeAccepteeApplique() {
        ChangementPartenaireService s = service(2026);
        s.demander(client, p2, null);
        evenements.clear();
        Long demandeId = s.demander(client, p3, "x").demandeId();

        s.trancher(demandeId, true);

        assertThat(partenaireDuClient()).isEqualTo(p3);
        assertThat(evenements).containsExactly(new ChangementPartenaireEvenement(client, p2, p3));
        assertThat(demandes.findById(demandeId).orElseThrow().getStatut()).isEqualTo("ACCEPTEE");
        assertThat(liens.findByClientSocieteId(client).orElseThrow().getChangementsAnneeCourante()).isEqualTo(2);
    }

    @Test
    void demandeRefuseeLaisseLeLienInchange() {
        ChangementPartenaireService s = service(2026);
        s.demander(client, p2, null);
        evenements.clear();
        Long demandeId = s.demander(client, p3, "x").demandeId();

        s.trancher(demandeId, false);

        assertThat(partenaireDuClient()).isEqualTo(p2);
        assertThat(evenements).isEmpty();
        assertThat(demandes.findById(demandeId).orElseThrow().getStatut()).isEqualTo("REFUSEE");
    }

    @Test
    void trancherUneDemandeDejaTraiteeEstRefuse() {
        ChangementPartenaireService s = service(2026);
        s.demander(client, p2, null);
        Long demandeId = s.demander(client, p3, "x").demandeId();
        s.trancher(demandeId, false);

        assertThatThrownBy(() -> s.trancher(demandeId, true)).isInstanceOf(IllegalStateException.class);
        assertThat(partenaireDuClient()).isEqualTo(p2);
    }

    @Test
    void premierChangementDeLAnneeSuivanteEstAppliqueEtRemetLeCompteur() {
        service(2026).demander(client, p2, null);

        var res = service(2027).demander(client, p3, null);

        assertThat(res.applique()).isTrue();
        assertThat(partenaireDuClient()).isEqualTo(p3);
        LienClient lien = liens.findByClientSocieteId(client).orElseThrow();
        assertThat(lien.getChangementsAnneeCourante()).isEqualTo(1);
        assertThat(lien.getAnneeReference()).isEqualTo(2027);
    }

    @Test
    void nouveauPartenaireSansCapaciteEstRejeteAvantToutEcriture() {
        assertThatThrownBy(() -> service(2026).demander(client, sansCapacite, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(partenaireDuClient()).isEqualTo(p1);
        assertThat(demandes.count()).isZero();
        assertThat(evenements).isEmpty();
    }

    @Test
    void memePartenaireIdsNulsEtClientSansLienSontRejetes() {
        ChangementPartenaireService s = service(2026);
        assertThatThrownBy(() -> s.demander(client, p1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.demander(null, p2, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.demander(client, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.trancher(null, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.demander(sansCapacite, p2, null)).isInstanceOf(IllegalStateException.class);
        assertThat(demandes.count()).isZero();
    }
}
