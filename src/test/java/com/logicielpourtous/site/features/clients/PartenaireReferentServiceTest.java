package com.logicielpourtous.site.features.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Rattachement d'un client a son partenaire referent et partenaire par defaut du serveur. */
@SpringBootTest
@Transactional
class PartenaireReferentServiceTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private CapaciteSocieteRepository capacites;
    @Autowired private LienClientRepository liens;

    private Long partenaire;
    private Long client;
    private Long sansCapacite;

    @BeforeEach
    void preparer() {
        partenaire = societe("PR1", "FRPR1");
        client = societe("PR2", "FRPR2");
        sansCapacite = societe("PR3", "FRPR3");
        capacite(partenaire, TypeCapacite.PARTENAIRE);
    }

    private PartenaireReferentService service(String propriete) {
        return new PartenaireReferentService(liens, capacites, propriete);
    }

    private Long societe(String siren, String tva) {
        return jdbc.queryForObject("INSERT INTO societes_identites (siren, tva, type) VALUES (?, ?, 'CLIENT') RETURNING id",
                Long.class, siren, tva);
    }

    private void capacite(Long societeId, TypeCapacite c) {
        CapaciteSociete ligne = new CapaciteSociete();
        ligne.setSocieteId(societeId);
        ligne.setCapacite(c);
        capacites.save(ligne);
    }

    @Test
    void rattacherCreeLeLienEtAjouteLaCapaciteClient() {
        LienClient lien = service("").rattacher(client, partenaire, "PARTENAIRE");

        assertThat(lien.getId()).isNotNull();
        assertThat(lien.getPartenaireSocieteId()).isEqualTo(partenaire);
        assertThat(lien.getOrigine()).isEqualTo("PARTENAIRE");
        assertThat(capacites.existsBySocieteIdAndCapacite(client, TypeCapacite.CLIENT)).isTrue();
    }

    @Test
    void rattacherNeDupliquePasLaCapaciteClientExistante() {
        capacite(client, TypeCapacite.CLIENT);
        service("").rattacher(client, partenaire, "PARTENAIRE");
        assertThat(capacites.findBySocieteId(client)).hasSize(1);
    }

    @Test
    void unPartenaireNePeutPasEtreSonPropreReferent() {
        assertThatThrownBy(() -> service("").rattacher(partenaire, partenaire, "PARTENAIRE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(liens.findByClientSocieteId(partenaire)).isEmpty();
    }

    @Test
    void rattacherUnClientDejaRattacheEstRefuse() {
        PartenaireReferentService s = service("");
        s.rattacher(client, partenaire, "PARTENAIRE");
        assertThatThrownBy(() -> s.rattacher(client, partenaire, "PARTENAIRE"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rattacherRefuseUnPartenaireSansCapacitePartenaireSansRienEcrire() {
        assertThatThrownBy(() -> service("").rattacher(client, sansCapacite, "PARTENAIRE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(liens.findByClientSocieteId(client)).isEmpty();
        assertThat(capacites.existsBySocieteIdAndCapacite(client, TypeCapacite.CLIENT)).isFalse();
    }

    @Test
    void rattacherAuDefautUtiliseLePartenaireConfigure() {
        LienClient lien = service(String.valueOf(partenaire)).rattacherAuDefaut(client);
        assertThat(lien.getPartenaireSocieteId()).isEqualTo(partenaire);
        assertThat(lien.getOrigine()).isEqualTo("INSCRIPTION");
    }

    @Test
    void rattacherAuDefautSansProprieteLeveUneExceptionEtNeCreeAucunLien() {
        assertThatThrownBy(() -> service("").rattacherAuDefaut(client))
                .isInstanceOf(PartenaireDefautAbsentException.class);
        assertThatThrownBy(() -> service("  ").partenaireDefaut())
                .isInstanceOf(PartenaireDefautAbsentException.class);
        assertThat(liens.findByClientSocieteId(client)).isEmpty();
    }

    @Test
    void proprieteDesignantUneSocieteSansCapacitePartenaireLeveLaMemeException() {
        assertThatThrownBy(() -> service(String.valueOf(sansCapacite)).rattacherAuDefaut(client))
                .isInstanceOf(PartenaireDefautAbsentException.class);
        assertThatThrownBy(() -> service("abc").partenaireDefaut())
                .isInstanceOf(PartenaireDefautAbsentException.class);
        assertThat(liens.findByClientSocieteId(client)).isEmpty();
    }
}
