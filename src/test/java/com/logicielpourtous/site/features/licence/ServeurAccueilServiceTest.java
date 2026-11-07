package com.logicielpourtous.site.features.licence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.logicielpourtous.site.features.commun.ServerRole;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantManagementService;
import com.logicielpourtous.site.features.commun.TenantRepository;

/**
 * Regles de rattachement tenant -> serveur d'accueil et tenant client -> tenant partenaire, serveur
 * par defaut des nouveaux tenants, normalisation de l'URL publique d'un serveur.
 */
@SpringBootTest
@Transactional
class ServeurAccueilServiceTest {

    @Autowired private ServeurAccueilService service;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private LicenceServeurRepository licenceServeurRepository;
    @Autowired private TenantManagementService tenantManagementService;
    @Autowired private DataSource dataSource;

    private Tenant tenant(String nom) {
        Tenant t = new Tenant();
        t.setName(nom);
        t.setSchemaName("t_srv_" + UUID.randomUUID().toString().replace("-", ""));
        return tenantRepository.saveAndFlush(t);
    }

    private LicenceServeur serveur(LicenceServeur.LicenceServeurStatut statut) {
        LicenceServeur s = new LicenceServeur();
        s.setRole(ServerRole.PARTNER);
        s.setIdentifiantInstallationStable(UUID.randomUUID().toString());
        s.setAdresseDeclaree("http://100.64.0.3:8072");
        s.setStatut(statut);
        return licenceServeurRepository.saveAndFlush(s);
    }

    @Test
    void serveurParDefaut_absentAuDepart() {
        assertThat(service.serveurParDefautId()).isEmpty();
    }

    @Test
    void definirServeurParDefaut_conserveLeServeurActifPuisPermetDeLEffacer() {
        LicenceServeur actif = serveur(LicenceServeur.LicenceServeurStatut.ACTIF);

        service.definirServeurParDefaut(actif.getId());
        assertThat(service.serveurParDefautId()).contains(actif.getId());

        service.definirServeurParDefaut(null);
        assertThat(service.serveurParDefautId()).isEmpty();
    }

    @Test
    void definirServeurParDefaut_refuseUnServeurRevoqueOuInconnu() {
        LicenceServeur revoque = serveur(LicenceServeur.LicenceServeurStatut.REVOQUE);

        assertThatThrownBy(() -> service.definirServeurParDefaut(revoque.getId()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.definirServeurParDefaut(987654321L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.serveurParDefautId()).isEmpty();
    }

    private void revoquer(LicenceServeur serveur) {
        serveur.setStatut(LicenceServeur.LicenceServeurStatut.REVOQUE);
        licenceServeurRepository.saveAndFlush(serveur);
    }

    @Test
    void serveurParDefaut_ignoreUnServeurRevoqueApresCoup() {
        LicenceServeur actif = serveur(LicenceServeur.LicenceServeurStatut.ACTIF);
        service.definirServeurParDefaut(actif.getId());

        revoquer(actif);

        assertThat(service.serveurParDefautId()).isEmpty();
    }

    @Test
    void rattacher_conserveUnServeurDevenuRevoqueQuandOnNeChangeQueLePartenaire() {
        LicenceServeur serveur = serveur(LicenceServeur.LicenceServeurStatut.ACTIF);
        Tenant partenaire = tenant("Partenaire");
        Tenant client = tenant("Client");
        service.rattacherTenant(client.getId(), serveur.getId(), null);
        revoquer(serveur);

        // Le serveur est desormais revoque ; enregistrer le formulaire pour ne changer que le partenaire
        // ne doit ni echouer ni deplacer silencieusement le tenant vers le Maitre.
        service.rattacherTenant(client.getId(), serveur.getId(), partenaire.getId());

        Tenant relu = tenantRepository.findById(client.getId()).orElseThrow();
        assertThat(relu.getServeurLicenceId()).isEqualTo(serveur.getId());
        assertThat(relu.getTenantPartenaireId()).isEqualTo(partenaire.getId());
    }

    @Test
    void rattacher_refuseToujoursDeChoisirUnServeurRevoqueNouveau() {
        LicenceServeur revoque = serveur(LicenceServeur.LicenceServeurStatut.REVOQUE);
        Tenant t = tenant("T");

        assertThatThrownBy(() -> service.rattacherTenant(t.getId(), revoque.getId(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nouveauTenant_neRecoitPasUnServeurParDefautDevenuRevoque() throws Exception {
        LicenceServeur actif = serveur(LicenceServeur.LicenceServeurStatut.ACTIF);
        service.definirServeurParDefaut(actif.getId());
        revoquer(actif);
        String schema = "t_rev_" + UUID.randomUUID().toString().replace("-", "");
        try {
            tenantManagementService.createNewTenant("Apres revocation", schema);

            assertThat(tenantRepository.findBySchemaName(schema).orElseThrow().getServeurLicenceId()).isNull();
        } finally {
            try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
                s.executeUpdate("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void rattacher_accepteEtConserveServeurEtPartenaire() {
        Tenant partenaire = tenant("Partenaire");
        Tenant client = tenant("Client");
        LicenceServeur actif = serveur(LicenceServeur.LicenceServeurStatut.ACTIF);

        service.rattacherTenant(client.getId(), actif.getId(), partenaire.getId());

        Tenant relu = tenantRepository.findById(client.getId()).orElseThrow();
        assertThat(relu.getServeurLicenceId()).isEqualTo(actif.getId());
        assertThat(relu.getTenantPartenaireId()).isEqualTo(partenaire.getId());
    }

    @Test
    void rattacher_nullRetireLesRattachements() {
        Tenant partenaire = tenant("Partenaire");
        Tenant client = tenant("Client");
        service.rattacherTenant(client.getId(), null, partenaire.getId());

        service.rattacherTenant(client.getId(), null, null);

        Tenant relu = tenantRepository.findById(client.getId()).orElseThrow();
        assertThat(relu.getServeurLicenceId()).isNull();
        assertThat(relu.getTenantPartenaireId()).isNull();
    }

    @Test
    void rattacher_refuseLeTenantCommeSonProprePartenaire() {
        Tenant t = tenant("Seul");

        assertThatThrownBy(() -> service.rattacherTenant(t.getId(), null, t.getId()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(tenantRepository.findById(t.getId()).orElseThrow().getTenantPartenaireId()).isNull();
    }

    @Test
    void rattacher_refuseUneChaineADeuxNiveaux() {
        Tenant a = tenant("A");
        Tenant b = tenant("B");
        Tenant c = tenant("C");
        service.rattacherTenant(b.getId(), null, a.getId());   // B depend de A

        // C dependrait de B, qui depend deja de A : deux niveaux
        assertThatThrownBy(() -> service.rattacherTenant(c.getId(), null, b.getId()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rattacher_refuseDeuxPartenairesMutuels() {
        Tenant a = tenant("A");
        Tenant b = tenant("B");
        service.rattacherTenant(b.getId(), null, a.getId());   // B depend de A

        assertThatThrownBy(() -> service.rattacherTenant(a.getId(), null, b.getId()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rattacher_refuseDeDonnerUnPartenaireAUnTenantQuiEstDejaPartenaire() {
        Tenant a = tenant("A");
        Tenant b = tenant("B");
        Tenant c = tenant("C");
        service.rattacherTenant(b.getId(), null, a.getId());   // A est partenaire de B

        // A dependrait de C alors que B depend de A : deux niveaux
        assertThatThrownBy(() -> service.rattacherTenant(a.getId(), null, c.getId()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rattacher_refuseUnServeurRevoqueUnServeurInconnuEtUnTenantInconnu() {
        Tenant t = tenant("T");
        LicenceServeur revoque = serveur(LicenceServeur.LicenceServeurStatut.REVOQUE);

        assertThatThrownBy(() -> service.rattacherTenant(t.getId(), revoque.getId(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.rattacherTenant(t.getId(), 987654321L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.rattacherTenant(987654321L, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normaliser_accepteUneUrlHttpsSansCheminAvecOuSansSlashFinalEtPort() {
        assertThat(service.normaliserUrlPublique("https://app.exemple.com")).isEqualTo("https://app.exemple.com");
        assertThat(service.normaliserUrlPublique("  https://app.exemple.com/ ")).isEqualTo("https://app.exemple.com");
        assertThat(service.normaliserUrlPublique("https://app.exemple.com:8443")).isEqualTo("https://app.exemple.com:8443");
    }

    @Test
    void normaliser_videOuNullEfface() {
        assertThat(service.normaliserUrlPublique(null)).isNull();
        assertThat(service.normaliserUrlPublique("   ")).isNull();
    }

    @Test
    void normaliser_refuseHttpCheminIdentifiantsEspaceEtPortInvalide() {
        for (String invalide : new String[] {
                "http://app.exemple.com", "https://app.exemple.com/x", "https://app.exemple.com/?a=1",
                "https://u:p@app.exemple.com", "https://app exemple.com", "https://", "app.exemple.com",
                "https://app.exemple.com:99999", "https://app.exemple.com:0" }) {
            assertThatThrownBy(() -> service.normaliserUrlPublique(invalide))
                    .as(invalide)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void nouveauTenant_recoitLeServeurParDefaut() throws Exception {
        LicenceServeur actif = serveur(LicenceServeur.LicenceServeurStatut.ACTIF);
        service.definirServeurParDefaut(actif.getId());
        String schema = "t_defaut_" + UUID.randomUUID().toString().replace("-", "");
        try {
            tenantManagementService.createNewTenant("Nouveau", schema);

            Tenant cree = tenantRepository.findBySchemaName(schema).orElseThrow();
            assertThat(cree.getServeurLicenceId()).isEqualTo(actif.getId());
        } finally {
            // createNewTenant cree le schema par une connexion JDBC separee : la transaction du test ne
            // peut pas l'annuler, on le supprime donc explicitement (connexion hors transaction).
            try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
                s.executeUpdate("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void nouveauTenant_sansServeurParDefaut_resteRattacheAuMaitre() throws Exception {
        String schema = "t_sans_" + UUID.randomUUID().toString().replace("-", "");
        try {
            tenantManagementService.createNewTenant("Sans defaut", schema);

            assertThat(tenantRepository.findBySchemaName(schema).orElseThrow().getServeurLicenceId()).isNull();
        } finally {
            try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
                s.executeUpdate("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }
}
