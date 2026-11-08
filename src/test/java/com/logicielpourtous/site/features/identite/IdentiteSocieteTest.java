package com.logicielpourtous.site.features.identite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Identite normalisee d'une societe a partir de SIREN, SIRET et TVA saisis : fonctions pures. */
class IdentiteSocieteTest {

    private static void refuse(String siren, String siret, String tva, String champAttendu) {
        assertThatThrownBy(() -> IdentiteSociete.depuis(siren, siret, tva))
                .isInstanceOfSatisfying(IdentiteInvalideException.class,
                        e -> assertThat(e.champ()).as(e.getMessage()).isEqualTo(champAttendu));
    }

    @Test
    void depuis_societeFrancaise_normaliseSirenEtTva() {
        IdentiteSociete id = IdentiteSociete.depuis("404 833 048", null, "fr83404833048");

        assertThat(id).isEqualTo(new IdentiteSociete("404833048", "FR83404833048", false));
    }

    @Test
    void depuis_societeFrancaise_unSiretFournitLeSiren() {
        IdentiteSociete id = IdentiteSociete.depuis(null, "404 833 048 00022", "FR83404833048");

        assertThat(id).isEqualTo(new IdentiteSociete("404833048", "FR83404833048", false));
    }

    @Test
    void depuis_unSirenEtUnSiretCoherentsSontAcceptes() {
        IdentiteSociete id = IdentiteSociete.depuis("404833048", "40483304800022", "FR83404833048");

        assertThat(id.siren()).isEqualTo("404833048");
    }

    @Test
    void depuis_societeEtrangere_neGardeQueLaTva() {
        IdentiteSociete id = IdentiteSociete.depuis(null, null, "de 123 456 789");

        assertThat(id).isEqualTo(new IdentiteSociete(null, "DE123456789", true));
    }

    @Test
    void depuis_refuseUneTvaAbsente() {
        refuse("404833048", null, null, "tva");
        refuse("404833048", null, "   ", "tva");
    }

    @Test
    void depuis_refuseUneTvaFrancaiseDeBonneFormeMaisDeMauvaiseCle() {
        refuse("404833048", null, "FR84404833048", "tva");
    }

    @Test
    void depuis_refuseUneTvaFrancaiseQuiNeCorrespondPasAuSirenFourni() {
        refuse("404833048", null, "FR44732829320", "tva");
    }

    @Test
    void depuis_refuseUnSirenInvalideAvecUneTvaFrancaise() {
        refuse("123456789", null, "FR32123456789", "siren");
    }

    @Test
    void depuis_refuseUnSiretInvalide() {
        refuse(null, "40483304800023", "FR83404833048", "siret");
    }

    @Test
    void depuis_refuseUnSiretDontLeSirenDiffereDuSirenFourni() {
        refuse("404833048", "55203253400018", "FR83404833048", "siret");
    }

    @Test
    void depuis_refuseUneTvaFrancaiseSansSirenNiSiret() {
        refuse(null, null, "FR83404833048", "siren");
    }

    @Test
    void depuis_refuseUnSirenAvecUneTvaEtrangere() {
        refuse("404833048", null, "DE123456789", "siren");
    }

    @Test
    void leConstructeurPublicAccepteUneIdentiteValideEtRefuseTouteIncoherence() {
        assertThat(new IdentiteSociete("404833048", "FR83404833048", false).siren()).isEqualTo("404833048");
        assertThat(new IdentiteSociete(null, "DE123456789", true).etrangere()).isTrue();

        // TVA non normalisee (minuscules) : sinon un doublon passerait les index uniques
        assertThatThrownBy(() -> new IdentiteSociete(null, "de123456789", true))
                .isInstanceOfSatisfying(IdentiteInvalideException.class, e -> assertThat(e.champ()).isEqualTo("tva"));
        // societe francaise declaree sans SIREN, ou etrangere avec un SIREN
        assertThatThrownBy(() -> new IdentiteSociete(null, "FR83404833048", false))
                .isInstanceOf(IdentiteInvalideException.class);
        assertThatThrownBy(() -> new IdentiteSociete("404833048", "DE123456789", true))
                .isInstanceOf(IdentiteInvalideException.class);
        // TVA francaise incoherente avec le SIREN, SIREN mal forme, TVA nulle
        assertThatThrownBy(() -> new IdentiteSociete("404833048", "FR84404833048", false))
                .isInstanceOf(IdentiteInvalideException.class);
        assertThatThrownBy(() -> new IdentiteSociete("40483304", "FR83404833048", false))
                .isInstanceOf(IdentiteInvalideException.class);
        assertThatThrownBy(() -> new IdentiteSociete(null, null, true))
                .isInstanceOf(IdentiteInvalideException.class);
    }

    @Test
    void depuis_refuseUneTvaDeHorsDeLUnionEuropeenne() {
        refuse(null, null, "US123456789", "tva");
    }
}
