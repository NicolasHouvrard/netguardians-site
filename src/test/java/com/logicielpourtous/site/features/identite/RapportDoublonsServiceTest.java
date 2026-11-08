package com.logicielpourtous.site.features.identite;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rapport des doublons deja presents dans les tables existantes (clients, tenants, ESN). Les lignes de test
 * utilisent des numeros fictifs (999...) pour ne pas se melanger aux donnees reelles de la base, et chaque
 * test ne regarde que ses propres groupes.
 */
@SpringBootTest
@Transactional
class RapportDoublonsServiceTest {

    @Autowired private RapportDoublonsService service;
    @Autowired private JdbcTemplate jdbc;

    private Long client(String nom, String siren, String siret, String tva) {
        return jdbc.queryForObject("INSERT INTO customers (active, companyname, siren, siret, vat_number) "
                + "VALUES (true, ?, ?, ?, ?) RETURNING id", Long.class, nom, siren, siret, tva);
    }

    private Long tenant(String nom, String siren, String tva) {
        return jdbc.queryForObject("INSERT INTO tenants (name, schema_name, siren, vat_number) "
                + "VALUES (?, ?, ?, ?) RETURNING id", Long.class,
                nom, "t_rap_" + UUID.randomUUID().toString().replace("-", ""), siren, tva);
    }

    private Long esn(String nom, String siret, String tva) {
        return jdbc.queryForObject("INSERT INTO partner_esns (active, company_name, siret, vat_number) "
                + "VALUES (true, ?, ?, ?) RETURNING id", Long.class, nom, siret, tva);
    }

    private GroupeDoublon groupe(String nature, String cle) {
        List<GroupeDoublon> trouves = service.rapport().stream()
                .filter(g -> g.nature().equals(nature) && g.cle().equals(cle)).toList();
        assertThat(trouves).as("groupes " + nature + " " + cle).hasSizeLessThanOrEqualTo(1);
        return trouves.isEmpty() ? null : trouves.get(0);
    }

    @Test
    void deuxClientsDeMemeSiren_formentUnGroupe() {
        // customers.siren est limite a 9 caracteres : une saisie avec espaces ne peut pas y etre stockee ;
        // la normalisation des variantes est couverte par NumerosSocieteTest et par le test de TVA ci-dessous.
        Long a = client("Client A", "999000111", null, null);
        Long b = client("Client B", "999000111", null, null);

        GroupeDoublon g = groupe("SIREN", "999000111");

        assertThat(g).isNotNull();
        assertThat(g.lignes()).extracting(GroupeDoublon.LigneSource::id).containsExactlyInAnyOrder(a, b);
        assertThat(g.lignes()).extracting(GroupeDoublon.LigneSource::table).containsOnly("customers");
        assertThat(g.lignes()).extracting(GroupeDoublon.LigneSource::nom).containsExactlyInAnyOrder("Client A", "Client B");
    }

    @Test
    void unClientEtUnTenantDeMemeSiren_formentUnGroupeInterTables() {
        Long c = client("Client", "999000222", null, null);
        Long t = tenant("Tenant", "999000222", null);

        GroupeDoublon g = groupe("SIREN", "999000222");

        assertThat(g).isNotNull();
        assertThat(g.lignes()).extracting(GroupeDoublon.LigneSource::table)
                .containsExactlyInAnyOrder("customers", "tenants");
        assertThat(g.lignes()).extracting(GroupeDoublon.LigneSource::id).containsExactlyInAnyOrder(c, t);
    }

    @Test
    void unSiretEtUnSirenDeLaMemeSociete_formentUnGroupe() {
        Long e = esn("ESN", "99900033300012", null);
        Long c = client("Client", "999000333", null, null);

        GroupeDoublon g = groupe("SIREN", "999000333");

        assertThat(g).isNotNull();
        assertThat(g.lignes()).extracting(GroupeDoublon.LigneSource::table)
                .containsExactlyInAnyOrder("partner_esns", "customers");
        assertThat(g.lignes()).extracting(GroupeDoublon.LigneSource::id).containsExactlyInAnyOrder(e, c);
    }

    @Test
    void deuxEtrangersDeMemeTva_formentUnGroupeTva() {
        Long t = tenant("Fournisseur DE", null, "DE999000444");
        Long c = client("Fournisseur DE bis", null, null, "de 999 000 444");

        GroupeDoublon g = groupe("TVA", "DE999000444");

        assertThat(g).isNotNull();
        assertThat(g.lignes()).extracting(GroupeDoublon.LigneSource::id).containsExactlyInAnyOrder(t, c);
    }

    @Test
    void deuxSocietesFrancaisesDeMemeSiren_ParDeuxSirenEtTva_NeFormentPasAussiUnGroupeTva() {
        client("A", "999000555", null, "FR12999000555");
        client("B", "999000555", null, "FR12999000555");

        assertThat(groupe("SIREN", "999000555")).isNotNull();
        assertThat(groupe("TVA", "FR12999000555")).isNull();
    }

    @Test
    void uneSocieteSansIdentifiant_estIgnoree() {
        Long sans = client("Sans numero", null, null, null);
        client("Sans numero bis", "  ", null, "   ");

        boolean presente = service.rapport().stream()
                .flatMap(g -> g.lignes().stream()).anyMatch(l -> l.id().equals(sans) && l.table().equals("customers")
                        && l.nom().equals("Sans numero"));

        assertThat(presente).isFalse();
    }

    @Test
    void uneSeuleSociete_neFormePasDeGroupe() {
        client("Seule", "999000666", null, null);

        assertThat(groupe("SIREN", "999000666")).isNull();
    }
}
