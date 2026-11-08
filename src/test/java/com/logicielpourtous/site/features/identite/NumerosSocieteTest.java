package com.logicielpourtous.site.features.identite;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Controle des numeros d'identite d'une societe : fonctions pures, sans Spring. */
class NumerosSocieteTest {

    @Test
    void sirenValide_accepteUnSirenALaCleDeLuhnCorrecte() {
        assertThat(NumerosSociete.sirenValide("404833048")).isTrue();
        assertThat(NumerosSociete.sirenValide("732829320")).isTrue();
        assertThat(NumerosSociete.sirenValide("552032534")).isTrue();
    }

    @Test
    void sirenValide_refuseCleFausseLongueurEtCaracteresInterdits() {
        assertThat(NumerosSociete.sirenValide("123456789")).isFalse();
        assertThat(NumerosSociete.sirenValide("40483304")).isFalse();
        assertThat(NumerosSociete.sirenValide("4048330480")).isFalse();
        assertThat(NumerosSociete.sirenValide("abcdefghi")).isFalse();
        assertThat(NumerosSociete.sirenValide(null)).isFalse();
    }

    @Test
    void siretValide_accepteLuhnEtRefuseLeReste() {
        assertThat(NumerosSociete.siretValide("40483304800022")).isTrue();
        assertThat(NumerosSociete.siretValide("40483304800006")).isTrue();
        assertThat(NumerosSociete.siretValide("55203253400018")).isTrue();
        assertThat(NumerosSociete.siretValide("40483304800023")).isFalse();
        assertThat(NumerosSociete.siretValide("4048330480002")).isFalse();
        assertThat(NumerosSociete.siretValide(null)).isFalse();
    }

    @Test
    void siretValide_exceptionLaPoste_sommeDesChiffresMultipleDeCinq() {
        // SIREN 356000000 : Luhn ne s'applique pas, la somme des chiffres doit etre un multiple de 5.
        assertThat(NumerosSociete.siretValide("35600000000001")).isTrue();
        assertThat(NumerosSociete.siretValide("35600000000000")).isFalse();
    }

    @Test
    void sirenDe_prendLesNeufPremiersChiffres() {
        assertThat(NumerosSociete.sirenDe("40483304800022")).isEqualTo("404833048");
    }

    @Test
    void chiffres_retireEspacesPointsEtTiretsEtRenvoieNullSiVide() {
        assertThat(NumerosSociete.chiffres(" 552.032-534 ")).isEqualTo("552032534");
        assertThat(NumerosSociete.chiffres("552 032 534")).isEqualTo("552032534");
        assertThat(NumerosSociete.chiffres("   ")).isNull();
        assertThat(NumerosSociete.chiffres(null)).isNull();
    }

    @Test
    void tva_metEnMajusculesEtRetireLesSeparateurs() {
        assertThat(NumerosSociete.tva(" fr 27 552.032.534 ")).isEqualTo("FR27552032534");
        assertThat(NumerosSociete.tva("  ")).isNull();
        assertThat(NumerosSociete.tva(null)).isNull();
    }

    @Test
    void tvaFrancaise_calculeLaCleSurDeuxChiffres() {
        assertThat(NumerosSociete.tvaFrancaise("404833048")).isEqualTo("FR83404833048");
        assertThat(NumerosSociete.tvaFrancaise("732829320")).isEqualTo("FR44732829320");
        assertThat(NumerosSociete.tvaFrancaise("552032534")).isEqualTo("FR27552032534");
        // cle < 10 : zero initial
        assertThat(NumerosSociete.tvaFrancaise("100055433")).isEqualTo("FR05100055433");
    }

    @Test
    void tvaEtrangereValide_accepteLesPaysDeLUnionEuropeenneSaufLaFrance() {
        assertThat(NumerosSociete.tvaEtrangereValide("DE123456789")).isTrue();
        assertThat(NumerosSociete.tvaEtrangereValide("EL123456789")).isTrue();
        assertThat(NumerosSociete.tvaEtrangereValide("XI123456789")).isTrue();
        assertThat(NumerosSociete.tvaEtrangereValide("US123456789")).isFalse();
        assertThat(NumerosSociete.tvaEtrangereValide("DE1")).isFalse();
        assertThat(NumerosSociete.tvaEtrangereValide("FR83404833048")).isFalse();
        assertThat(NumerosSociete.tvaEtrangereValide("de123456789")).isFalse();
        assertThat(NumerosSociete.tvaEtrangereValide(null)).isFalse();
    }
}
