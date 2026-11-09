package com.logicielpourtous.site.features.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicielpourtous.site.features.auth.InscriptionSocieteService.DemandeClient;
import com.logicielpourtous.site.features.auth.InscriptionSocieteService.ResultatInscription;
import com.logicielpourtous.site.features.auth.RepriseRegistreService.BilanReprise;
import com.logicielpourtous.site.features.commun.Tenant;
import com.logicielpourtous.site.features.commun.TenantRepository;
import com.logicielpourtous.site.features.identite.IdentiteSociete;
import com.logicielpourtous.site.features.identite.NumerosSociete;
import com.logicielpourtous.site.features.identite.RegistreSocietes;
import com.logicielpourtous.site.features.identite.SocieteIdentite;
import com.logicielpourtous.site.features.identite.SocieteIdentiteRepository;
import com.logicielpourtous.site.features.identite.TypeSociete;

/**
 * Reprise des societes existantes dans le registre. Volontairement SANS {@code @Transactional} : le service
 * valide une transaction par fiche. La base de test peut contenir des fiches historiques, que la reprise
 * traite aussi : chaque test releve donc les lignes de registre existantes avant, et supprime apres coup
 * tout ce qui a ete ajoute (registre, demandes, fiches et tenants crees par le test).
 */
@SpringBootTest
class RepriseRegistreServiceTest {

    @Autowired private RepriseRegistreService service;
    @Autowired private InscriptionSocieteService inscription;
    @Autowired private RegistreSocietes registre;
    @Autowired private SocieteIdentiteRepository identites;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private JdbcTemplate jdbc;

    private Set<Long> registreInitial;
    private final List<Long> customers = new ArrayList<>();
    private final List<Long> esns = new ArrayList<>();
    private final List<Long> tenants = new ArrayList<>();

    @BeforeEach
    void releverLeRegistre() {
        registreInitial = new HashSet<>(jdbc.queryForList("SELECT id FROM societes_identites", Long.class));
    }

    @AfterEach
    void nettoyer() {
        List<Long> ajoutes = new ArrayList<>(jdbc.queryForList("SELECT id FROM societes_identites", Long.class));
        ajoutes.removeAll(registreInitial);
        for (Long id : ajoutes) {
            jdbc.update("DELETE FROM demandes_societe_connue WHERE societe_identite_id = ?", id);
            jdbc.update("DELETE FROM societes_identites WHERE id = ?", id);
        }
        customers.forEach(id -> jdbc.update("DELETE FROM customers WHERE id = ?", id));
        esns.forEach(id -> jdbc.update("DELETE FROM partner_esns WHERE id = ?", id));
        tenants.forEach(id -> jdbc.update("DELETE FROM tenants WHERE id = ?", id));
    }

    // ── Jeux de donnees ──────────────────────────────────────────────────

    private static String nouveauSiren() {
        while (true) {
            String base = String.format("%08d", ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999));
            for (int cle = 0; cle <= 9; cle++) {
                String siren = base + cle;
                if (NumerosSociete.sirenValide(siren) && !siren.startsWith("356000000")) {
                    return siren;
                }
            }
        }
    }

    private static String siretDe(String siren) {
        for (int cle = 0; cle <= 9; cle++) {
            String siret = siren + "0001" + cle;
            if (NumerosSociete.siretValide(siret)) {
                return siret;
            }
        }
        throw new IllegalStateException("aucun SIRET valide pour " + siren);
    }

    private Long tenant() {
        Tenant t = new Tenant();
        t.setName("Tenant reprise test");
        t.setSchemaName("test_reprise_" + UUID.randomUUID());
        Long id = tenantRepository.save(t).getId();
        tenants.add(id);
        return id;
    }

    private Long client(String nom, String siren, String tva, Long tenantId) {
        Long id = jdbc.queryForObject(
                "INSERT INTO customers (active, companyname, siren, vat_number, tenant_id) VALUES (true, ?, ?, ?, ?) RETURNING id",
                Long.class, nom, siren, tva, tenantId);
        customers.add(id);
        return id;
    }

    private Long esn(String nom, String siret, String tva) {
        Long id = jdbc.queryForObject(
                "INSERT INTO partner_esns (active, company_name, siret, vat_number) VALUES (true, ?, ?, ?) RETURNING id",
                Long.class, nom, siret, tva);
        esns.add(id);
        return id;
    }

    private SocieteIdentite ligne(String siren) {
        return identites.findBySiren(siren).orElse(null);
    }

    private long nbLignes(String siren) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM societes_identites WHERE siren = ?", Long.class, siren);
    }

    // ── Tests ────────────────────────────────────────────────────────────

    @Test
    void clientDUnPartenaire_estReprisAvecCeTenantPourProprietaire() {
        String siren = nouveauSiren();
        Long tenantId = tenant();
        Long id = client("Client Partenaire", siren, NumerosSociete.tvaFrancaise(siren), tenantId);

        BilanReprise bilan = service.reprendre(false);

        assertThat(bilan.reprises()).isGreaterThanOrEqualTo(1);
        SocieteIdentite l = ligne(siren);
        assertThat(l).isNotNull();
        assertThat(l.getType()).isEqualTo(TypeSociete.CLIENT);
        assertThat(l.getProprietaireTenantId()).isEqualTo(tenantId);
        assertThat(l.getFicheType()).isEqualTo("customers");
        assertThat(l.getFicheId()).isEqualTo(id);
    }

    @Test
    void clientSansTenant_estReprisAvecLeMaitrePourProprietaire() {
        String siren = nouveauSiren();
        client("Client Maitre", siren, NumerosSociete.tvaFrancaise(siren), null);

        service.reprendre(false);

        SocieteIdentite l = ligne(siren);
        assertThat(l).isNotNull();
        assertThat(l.getType()).isEqualTo(TypeSociete.CLIENT);
        assertThat(l.getProprietaireTenantId()).isNull();
    }

    @Test
    void esnPartenaire_estRepriseCommePartenaireSansProprietaire_numerosNormalises() {
        String siren = nouveauSiren();
        String siret = siretDe(siren);
        Long id = esn("Esn Reprise", siret.substring(0, 3) + " " + siret.substring(3),
                NumerosSociete.tvaFrancaise(siren).toLowerCase());

        service.reprendre(false);

        SocieteIdentite l = ligne(siren);
        assertThat(l).isNotNull();
        assertThat(l.getType()).isEqualTo(TypeSociete.PARTENAIRE);
        assertThat(l.getProprietaireTenantId()).isNull();
        assertThat(l.getFicheType()).isEqualTo("partner_esns");
        assertThat(l.getFicheId()).isEqualTo(id);
    }

    @Test
    void clientATvaEtrangere_estRepriscommeFournisseur() {
        String tva = "DE" + ThreadLocalRandom.current().nextLong(100_000_000L, 999_999_999L);
        Long id = client("Client Allemand", null, tva, null);

        service.reprendre(false);

        SocieteIdentite l = identites.findByTva(tva).orElse(null);
        assertThat(l).isNotNull();
        assertThat(l.getType()).isEqualTo(TypeSociete.FOURNISSEUR);
        assertThat(l.getSiren()).isNull();
        assertThat(l.getFicheId()).isEqualTo(id);
    }

    @Test
    void ficheAuxNumerosInvalides_estIgnoreeEtListee() {
        String siren = nouveauSiren();
        // TVA dont la cle ne correspond pas au SIREN
        Long id = client("Client Invalide", siren, "FR00" + siren, null);

        BilanReprise bilan = service.reprendre(false);

        assertThat(bilan.ignoreesInvalides()).isGreaterThanOrEqualTo(1);
        assertThat(bilan.details()).anyMatch(d -> d.contains("customers #" + id));
        assertThat(nbLignes(siren)).isZero();
    }

    @Test
    void ficheSansTva_estIgnoreeEtListee_sansTvaDeduite() {
        String siren = nouveauSiren();
        Long id = client("Client Sans TVA", siren, null, null);

        BilanReprise bilan = service.reprendre(false);

        assertThat(bilan.details()).anyMatch(d -> d.contains("customers #" + id));
        assertThat(nbLignes(siren)).isZero();
    }

    @Test
    void deuxFichesDeMemeSiren_aucuneRepriseEtListeesEnDoublon() {
        String siren = nouveauSiren();
        String tva = NumerosSociete.tvaFrancaise(siren);
        Long a = client("Doublon A", siren, tva, null);
        Long b = client("Doublon B", siren, tva, null);

        BilanReprise bilan = service.reprendre(false);

        assertThat(nbLignes(siren)).isZero();
        assertThat(bilan.ignoreesDoublon()).isGreaterThanOrEqualTo(2);
        assertThat(bilan.details()).anyMatch(d -> d.contains("customers #" + a));
        assertThat(bilan.details()).anyMatch(d -> d.contains("customers #" + b));
    }

    @Test
    void societeDejaAuRegistreParUneAutreFiche_estListeeEnDoublonSansEtreModifiee() {
        String siren = nouveauSiren();
        String tva = NumerosSociete.tvaFrancaise(siren);
        registre.enregistrer(TypeSociete.PARTENAIRE, IdentiteSociete.depuis(siren, null, tva), null);
        Long id = client("Client Deja Connu", siren, tva, null);

        BilanReprise bilan = service.reprendre(false);

        assertThat(bilan.details()).anyMatch(d -> d.contains("customers #" + id));
        SocieteIdentite l = ligne(siren);
        assertThat(l.getType()).isEqualTo(TypeSociete.PARTENAIRE);
        assertThat(l.getFicheId()).isNull();
        assertThat(nbLignes(siren)).isEqualTo(1);
    }

    @Test
    void simulation_n_ecritRien() {
        String siren = nouveauSiren();
        client("Client Simule", siren, NumerosSociete.tvaFrancaise(siren), null);
        long avant = jdbc.queryForObject("SELECT COUNT(*) FROM societes_identites", Long.class);

        BilanReprise bilan = service.reprendre(true);

        assertThat(bilan.reprises()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM societes_identites", Long.class)).isEqualTo(avant);
        assertThat(nbLignes(siren)).isZero();
    }

    @Test
    void deuxiemeExecution_nEnRepreRien() {
        String siren = nouveauSiren();
        client("Client Idempotent", siren, NumerosSociete.tvaFrancaise(siren), null);

        BilanReprise premier = service.reprendre(false);
        long apresPremier = jdbc.queryForObject("SELECT COUNT(*) FROM societes_identites", Long.class);
        BilanReprise second = service.reprendre(false);

        assertThat(premier.reprises()).isGreaterThanOrEqualTo(1);
        assertThat(second.reprises()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM societes_identites", Long.class)).isEqualTo(apresPremier);
        assertThat(nbLignes(siren)).isEqualTo(1);
    }

    @Test
    void apresLaReprise_inscrireClientAvecLeSirenDUnClientExistant_estDejaConnuSansCreerDeClient() {
        String siren = nouveauSiren();
        String tva = NumerosSociete.tvaFrancaise(siren);
        client("Client Preexistant", siren, tva, null);
        service.reprendre(false);

        ResultatInscription resultat = inscription.inscrireClient(new DemandeClient("Autre Nom", "Bob Durand",
                "reprise-" + UUID.randomUUID() + "@example.com", "MotDePasse!1", siren, null, tva));

        assertThat(resultat).isInstanceOf(ResultatInscription.DejaConnue.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customers WHERE siren = ?", Long.class, siren))
                .isEqualTo(1L);
    }
}
