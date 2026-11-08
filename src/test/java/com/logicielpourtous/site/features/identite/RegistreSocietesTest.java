package com.logicielpourtous.site.features.identite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Registre unique des societes : une societe n'existe qu'une fois sur la plateforme, quelle que soit la
 * facon dont ses numeros ont ete saisis. {@code em.clear()} force la relecture depuis la base.
 */
@SpringBootTest
@Transactional
class RegistreSocietesTest {

    @Autowired private RegistreSocietes registre;
    @Autowired private SocieteIdentiteRepository repository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager em;

    private static IdentiteSociete francaise(String siren, String siret, String tva) {
        return IdentiteSociete.depuis(siren, siret, tva);
    }

    @Test
    void enregistrer_conserveLaSocieteEtLeProprietaire() {
        Long id = registre.enregistrer(TypeSociete.CLIENT, francaise("404833048", null, "FR83404833048"), 7L).getId();
        em.flush();
        em.clear();

        SocieteIdentite relue = repository.findById(id).orElseThrow();

        assertThat(relue.getSiren()).isEqualTo("404833048");
        assertThat(relue.getTva()).isEqualTo("FR83404833048");
        assertThat(relue.getType()).isEqualTo(TypeSociete.CLIENT);
        assertThat(relue.getProprietaireTenantId()).isEqualTo(7L);
        assertThat(relue.getCreeA()).isNotNull();
    }

    @Test
    void enregistrer_unDoublonDejaConnuNeMarquePasLaTransactionDeLAppelantEnRollbackOnly() {
        // Le formulaire d'inscription attrape l'exception pour afficher un message neutre et enregistrer
        // une notification dans la MEME transaction : elle ne doit pas etre condamnee a l'echec.
        registre.enregistrer(TypeSociete.CLIENT, francaise("404833048", null, "FR83404833048"), null);

        assertThatThrownBy(() -> registre.enregistrer(TypeSociete.CLIENT,
                francaise("404833048", null, "FR83404833048"), null))
                .isInstanceOf(SocieteDejaConnueException.class);

        assertThat(transactionManager.getTransaction(new DefaultTransactionDefinition()).isRollbackOnly())
                .as("transaction de l'appelant marquee rollback-only").isFalse();
    }

    @Test
    void enregistrer_refuseUnTypeNul() {
        assertThatThrownBy(() -> registre.enregistrer(null, francaise("404833048", null, "FR83404833048"), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void enregistrer_sansProprietaire_laSocieteAppartientAuMaitre() {
        Long id = registre.enregistrer(TypeSociete.PARTENAIRE, francaise("404833048", null, "FR83404833048"), null).getId();
        em.flush();
        em.clear();

        assertThat(repository.findById(id).orElseThrow().getProprietaireTenantId()).isNull();
    }

    @Test
    void enregistrer_refuseLeDoublonSousToutesLesFormesDeSaisie() {
        registre.enregistrer(TypeSociete.CLIENT, francaise("552 032 534", null, "FR27552032534"), 1L);

        // meme SIREN, autre ponctuation
        assertThatThrownBy(() -> registre.enregistrer(TypeSociete.CLIENT,
                francaise("552.032.534", null, "FR27552032534"), 2L))
                .isInstanceOf(SocieteDejaConnueException.class);
        // saisie par le SIRET
        assertThatThrownBy(() -> registre.enregistrer(TypeSociete.CLIENT,
                francaise(null, "55203253400018", "FR27552032534"), 2L))
                .isInstanceOf(SocieteDejaConnueException.class);
        // TVA en minuscules et avec espaces
        assertThatThrownBy(() -> registre.enregistrer(TypeSociete.PARTENAIRE,
                francaise("552032534", null, "fr 27 552032534"), null))
                .isInstanceOf(SocieteDejaConnueException.class);
    }

    @Test
    void enregistrer_refuseUneSocieteEtrangereDejaConnueParSaTva() {
        registre.enregistrer(TypeSociete.FOURNISSEUR, IdentiteSociete.depuis(null, null, "DE123456789"), null);

        assertThatThrownBy(() -> registre.enregistrer(TypeSociete.FOURNISSEUR,
                IdentiteSociete.depuis(null, null, "de 123 456 789"), null))
                .isInstanceOf(SocieteDejaConnueException.class);
    }

    @Test
    void enregistrer_uneSocieteEtrangereNEstQuUnFournisseur() {
        IdentiteSociete etrangere = IdentiteSociete.depuis(null, null, "NL123456789B01");

        assertThatThrownBy(() -> registre.enregistrer(TypeSociete.CLIENT, etrangere, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registre.enregistrer(TypeSociete.PARTENAIRE, etrangere, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(registre.enregistrer(TypeSociete.FOURNISSEUR, etrangere, null).getId()).isNotNull();
    }

    @Test
    void plusieursFournisseursEtrangersCoexistent_leSirenAbsentNEstPasUneCleCommune() {
        registre.enregistrer(TypeSociete.FOURNISSEUR, IdentiteSociete.depuis(null, null, "DE123456789"), null);
        registre.enregistrer(TypeSociete.FOURNISSEUR, IdentiteSociete.depuis(null, null, "BE0123456789"), null);

        assertThat(repository.findByTva("DE123456789")).isPresent();
        assertThat(repository.findByTva("BE0123456789")).isPresent();
    }

    @Test
    void trouver_renvoieVideSiLaSocieteEstInconnue() {
        assertThat(registre.trouver(francaise("404833048", null, "FR83404833048"))).isEmpty();
    }

    @Test
    void trouver_retrouveLaSocieteParSirenOuParTva() {
        registre.enregistrer(TypeSociete.CLIENT, francaise("404833048", null, "FR83404833048"), null);

        assertThat(registre.trouver(francaise(null, "40483304800006", "FR83404833048"))).isPresent();
        assertThat(registre.trouver(IdentiteSociete.depuis(null, null, "DE123456789"))).isEmpty();
    }

    @Test
    void laBaseRefuseUnSirenEnDoubleMemeSansLeControleApplicatif() {
        jdbc.update("INSERT INTO societes_identites (siren, tva, type, cree_a) VALUES (?, ?, ?, now())",
                "404833048", "FR83404833048", "CLIENT");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO societes_identites (siren, tva, type, cree_a) VALUES (?, ?, ?, now())",
                "404833048", "FR99999999999", "CLIENT"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void laBaseRefuseUneTvaEnDoubleMemeSansLeControleApplicatif() {
        jdbc.update("INSERT INTO societes_identites (siren, tva, type, cree_a) VALUES (?, ?, ?, now())",
                null, "DE123456789", "FOURNISSEUR");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO societes_identites (siren, tva, type, cree_a) VALUES (?, ?, ?, now())",
                null, "DE123456789", "FOURNISSEUR"))
                .isInstanceOf(DuplicateKeyException.class);
    }
}
