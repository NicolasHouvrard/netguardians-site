package com.logicielpourtous.site.features.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.logicielpourtous.site.features.auth.RepriseRegistreService.BilanReprise;
import com.logicielpourtous.site.features.identite.EnregistrementConcurrentException;
import com.logicielpourtous.site.features.identite.NumerosSociete;
import com.logicielpourtous.site.features.identite.RegistreSocietes;

/**
 * Isolation par fiche : une fiche dont l'enregistrement echoue (conflit, erreur inattendue apres ecriture)
 * n'arrete pas la reprise, n'annule pas les fiches deja validees et n'est pas validee elle-meme.
 */
@SpringBootTest
class RepriseRegistreIsolationTest {

    @Autowired private RepriseRegistreService service;
    @MockitoSpyBean private RegistreSocietes registre;
    @Autowired private JdbcTemplate jdbc;

    private Set<Long> registreInitial;
    private final List<Long> customers = new ArrayList<>();

    @BeforeEach
    void releverLeRegistre() {
        registreInitial = new HashSet<>(jdbc.queryForList("SELECT id FROM societes_identites", Long.class));
    }

    @AfterEach
    void nettoyer() {
        Mockito.reset(registre);
        List<Long> ajoutes = new ArrayList<>(jdbc.queryForList("SELECT id FROM societes_identites", Long.class));
        ajoutes.removeAll(registreInitial);
        ajoutes.forEach(id -> jdbc.update("DELETE FROM societes_identites WHERE id = ?", id));
        customers.forEach(id -> jdbc.update("DELETE FROM customers WHERE id = ?", id));
    }

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

    private Long client(String nom, String siren) {
        Long id = jdbc.queryForObject(
                "INSERT INTO customers (active, companyname, siren, vat_number) VALUES (true, ?, ?, ?) RETURNING id",
                Long.class, nom, siren, NumerosSociete.tvaFrancaise(siren));
        customers.add(id);
        return id;
    }

    private long nbLignes(String siren) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM societes_identites WHERE siren = ?", Long.class, siren);
    }

    @Test
    void uneFicheEnEchec_n_arretePasLeLotNiNAnnuleLesAutres() {
        String sirenAvant = nouveauSiren();
        String sirenErreur = nouveauSiren();
        String sirenConflit = nouveauSiren();
        String sirenApres = nouveauSiren();
        client("Avant", sirenAvant);
        Long idErreur = client("Erreur", sirenErreur);
        Long idConflit = client("Conflit", sirenConflit);
        client("Apres", sirenApres);

        doAnswer(inv -> {
            var identite = (com.logicielpourtous.site.features.identite.IdentiteSociete) inv.getArgument(1);
            if (sirenConflit.equals(identite.siren())) {
                throw new EnregistrementConcurrentException(new RuntimeException("conflit simule"));
            }
            Object resultat = inv.callRealMethod();   // ecrit (flush) dans la transaction de la fiche...
            if (sirenErreur.equals(identite.siren())) {
                throw new IllegalStateException("erreur simulee apres ecriture");   // ...puis echoue
            }
            return resultat;
        }).when(registre).enregistrer(any(), any(), any());

        BilanReprise bilan = service.reprendre(false);

        // Les fiches saines, avant et apres, sont validees ; les deux fiches en echec ne laissent rien.
        assertThat(nbLignes(sirenAvant)).isEqualTo(1);
        assertThat(nbLignes(sirenApres)).isEqualTo(1);
        assertThat(nbLignes(sirenErreur)).isZero();
        assertThat(nbLignes(sirenConflit)).isZero();

        // Les deux fiches en echec sont listees, aucune n'est comptee comme reprise.
        assertThat(bilan.details()).anyMatch(d -> d.contains("customers #" + idErreur));
        assertThat(bilan.details()).anyMatch(d -> d.contains("customers #" + idConflit));
        assertThat(bilan.reprises()).isGreaterThanOrEqualTo(2);
        assertThat(bilan.ignoreesInvalides()).isGreaterThanOrEqualTo(1);
        assertThat(bilan.ignoreesDoublon()).isGreaterThanOrEqualTo(1);
    }
}
