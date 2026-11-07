package com.logicielpourtous.site.features.licence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.logicielpourtous.site.features.commun.ServerRole;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Modele de federation (etape 1) : les nouvelles colonnes (serveur d'accueil et tenant partenaire
 * d'un tenant, URL publique d'un serveur) et la table a une ligne du serveur par defaut doivent
 * exister (ddl-auto=validate) et se relire telles qu'ecrites. {@code em.clear()} force la relecture
 * depuis la base plutot que depuis le cache de session.
 */
@SpringBootTest
@Transactional
class FederationModeleTest {

    @Autowired private TenantRepository tenantRepository;
    @Autowired private LicenceServeurRepository licenceServeurRepository;
    @Autowired private ParametreFederationRepository parametreFederationRepository;
    @PersistenceContext private EntityManager em;

    @Test
    void tenant_conserveServeurEtPartenaire() {
        Tenant tenant = new Tenant();
        tenant.setName("Tenant federation");
        tenant.setSchemaName("t_fed_" + UUID.randomUUID().toString().replace("-", ""));
        tenant.setServeurLicenceId(7L);
        tenant.setTenantPartenaireId(9L);
        Long id = tenantRepository.saveAndFlush(tenant).getId();
        em.clear();

        Tenant relu = tenantRepository.findById(id).orElseThrow();

        assertThat(relu.getServeurLicenceId()).isEqualTo(7L);
        assertThat(relu.getTenantPartenaireId()).isEqualTo(9L);
    }

    @Test
    void licenceServeur_conserveUrlPublique() {
        LicenceServeur serveur = new LicenceServeur();
        serveur.setRole(ServerRole.PARTNER);
        serveur.setIdentifiantInstallationStable(UUID.randomUUID().toString());
        serveur.setAdresseDeclaree("http://100.64.0.3:8072");
        serveur.setStatut(LicenceServeur.LicenceServeurStatut.ACTIF);
        serveur.setUrlPublique("https://app.exemple.com");
        Long id = licenceServeurRepository.saveAndFlush(serveur).getId();
        em.clear();

        LicenceServeur relu = licenceServeurRepository.findById(id).orElseThrow();

        assertThat(relu.getUrlPublique()).isEqualTo("https://app.exemple.com");
    }

    @Test
    void parametreFederation_existeAvecId1ParMigration() {
        ParametreFederation parametre = parametreFederationRepository.findById(1L).orElseThrow();

        assertThat(parametre.getServeurDefautId()).isNull();
    }
}
