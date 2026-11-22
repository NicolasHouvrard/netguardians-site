package com.logicielpourtous.site.features.theme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.logicielpourtous.site.sdk.ManifestePlugin;

class ThemeValeursTest {

    @Test
    void valeursValidesAcceptees() {
        assertThat(ThemeValeurs.valider("couleur-primaire", "#1A2b3C")).isEqualTo("#1a2b3c");
        assertThat(ThemeValeurs.valider("epaisseur-trait", "0")).isEqualTo("0");
        assertThat(ThemeValeurs.valider("epaisseur-trait", "4")).isEqualTo("4");
        assertThat(ThemeValeurs.valider("arrondi", "24")).isEqualTo("24");
        assertThat(ThemeValeurs.valider("taille-texte", "18")).isEqualTo("18");
        assertThat(ThemeValeurs.valider("liste-densite", "compact")).isEqualTo("compact");
        assertThat(ThemeValeurs.valider("police", "systeme")).isEqualTo("systeme");
        assertThat(ThemeValeurs.valider("liste-lignes-alternees", "oui")).isEqualTo("oui");
        assertThat(ThemeValeurs.valider("fiche-statut-style", "pilule")).isEqualTo("pilule");
        assertThat(ThemeValeurs.valider("tuile-arrondi", "32")).isEqualTo("32");
        assertThat(ThemeValeurs.valider("topbar-hauteur", " 36 ")).isEqualTo("36");
    }

    @ParameterizedTest
    @CsvSource({"epaisseur-trait,5", "arrondi,25", "taille-texte,19", "taille-texte,11", "topbar-hauteur,35",
            "topbar-hauteur,65", "tuile-taille-icone,23", "tuile-arrondi,33", "bouton-arrondi,25",
            "epaisseur-trait,-1", "arrondi,1.5", "arrondi,+3", "arrondi,0x10", "arrondi,1e1",
            "arrondi,99999999999999999999", "arrondi,12px"})
    void entiersHorsRegleRefuses(String cle, String valeur) {
        assertThatThrownBy(() -> ThemeValeurs.valider(cle, valeur)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"red;}body{display:none", "#12345", "#1234567", "url(javascript:alert(1))", "</style>",
            "#12345g", "red", "#12 456", "#ffffff;", "ref:couleur-fond", "var(--socle-couleur-fond)",
            "#ffffff\n", "\t#ffffff", "#fff\u0000fff", "#ffff\nff", "#ffffff\r", " #ffffff", "#ffffff‮"})
    void couleursHostilesRefusees(String valeur) {
        assertThatThrownBy(() -> ThemeValeurs.valider("couleur-primaire", valeur))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(valeur.trim()).doesNotContain("^#"));
    }

    @Test
    void enumsEtPoliceFermes() {
        for (String v : List.of("Compact", "large", "", "compact;", "normal\n")) {
            assertThatThrownBy(() -> ThemeValeurs.valider("liste-densite", v)).isInstanceOf(IllegalArgumentException.class);
        }
        for (String v : List.of("Arial", "inter;}", "'Inter'", "Inter", "systeme,x", "ref:police")) {
            assertThatThrownBy(() -> ThemeValeurs.valider("police", v)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> ThemeValeurs.valider("fiche-statut-style", "rond")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ThemeValeurs.valider("liste-lignes-alternees", "true")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cleInconnueVideNulleOuTropLongue() {
        assertThatThrownBy(() -> ThemeValeurs.valider("inconnue", "#ffffff")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ThemeValeurs.valider(null, "#ffffff")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ThemeValeurs.valider("couleur-primaire", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ThemeValeurs.valider("couleur-primaire", "   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ThemeValeurs.valider("couleur-primaire", null)).isInstanceOf(IllegalArgumentException.class);
        String longue = "#ffffff" + " ".repeat(40);
        assertThatThrownBy(() -> ThemeValeurs.valider("couleur-primaire", longue)).isInstanceOf(IllegalArgumentException.class);
        String quarante = "#ffffff" + "a".repeat(34);
        assertThatThrownBy(() -> ThemeValeurs.valider("couleur-primaire", quarante)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void messageNeRepeteNiLaValeurNiLaCle() {
        String hostile = "x;}body{display:none";
        assertThatThrownBy(() -> ThemeValeurs.valider("arrondi", hostile))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(hostile).doesNotContain("body"));
        assertThatThrownBy(() -> ThemeValeurs.valider("cle-inconnue-xyz", "1"))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("xyz"));
    }

    @Test
    void referencesRefuseesCommeValeurUtilisateurMaisValidesCommeDefaut() {
        assertThatThrownBy(() -> ThemeValeurs.valider("topbar-fond", "ref:couleur-primaire")).isInstanceOf(IllegalArgumentException.class);
        assertThat(ThemeValeurs.validerDefaut("topbar-fond", "ref:couleur-primaire")).isEqualTo("ref:couleur-primaire");
        assertThat(ThemeValeurs.validerDefaut("topbar-fond", "#ABCDEF")).isEqualTo("#abcdef");
        // la cible doit exister et etre du meme type
        assertThatThrownBy(() -> ThemeValeurs.validerDefaut("topbar-fond", "ref:inconnue")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ThemeValeurs.validerDefaut("topbar-fond", "ref:arrondi")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ThemeValeurs.validerDefaut("topbar-fond", "ref:topbar-fond")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ThemeValeurs.validerDefaut("topbar-fond", "ref:couleur-primaire;}")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rendu_cssFixe() {
        assertThat(ThemeValeurs.rendreCss("arrondi", "12")).isEqualTo("12px");
        assertThat(ThemeValeurs.rendreCss("couleur-fond", "#aabbcc")).isEqualTo("#aabbcc");
        assertThat(ThemeValeurs.rendreCss("liste-densite", "aere")).isEqualTo("aere");
        assertThat(ThemeValeurs.rendreCss("police", "systeme")).contains("system-ui");
        assertThat(ThemeValeurs.rendreCss("police", "inter")).startsWith("'Inter'");
        assertThat(ThemeValeurs.rendreCss("topbar-fond", "ref:couleur-primaire")).isEqualTo("var(--socle-couleur-primaire)");
    }

    @Test
    void lesPilesDePolicesSontSures() {
        for (String id : ThemeValeurs.policesAutorisees()) {
            String pile = ThemeValeurs.rendreCss("police", id);
            assertThat(pile).matches("[A-Za-z0-9\\-,' ]+");
        }
        assertThat(ThemeValeurs.policesAutorisees()).contains("systeme", "inter", "roboto", "source-sans-3");
    }

    @Test
    void cleListeFermeeEtGroupes() {
        assertThat(ThemeValeurs.cles()).hasSize(30).doesNotHaveDuplicates();
        assertThat(ThemeValeurs.cles()).allMatch(c -> c.matches("[a-z][a-z0-9-]*"));
        for (String c : ThemeValeurs.cles()) {
            assertThat(ThemeValeurs.GROUPES).containsKey(ThemeValeurs.groupe(c));
        }
        assertThat(ThemeValeurs.GROUPES.keySet()).containsExactly("general", "barre", "tuiles", "liste", "fiche", "boutons");
    }

    @Test
    void clesDesCarresDApplicationSuiventLesCouleursDuManifeste() {
        Set<String> attendues = ManifestePlugin.COULEURS_TUILE.stream().map(c -> "app-" + c).collect(Collectors.toSet());
        Set<String> presentes = ThemeValeurs.cles().stream().filter(c -> c.startsWith("app-")).collect(Collectors.toSet());
        assertThat(presentes).isEqualTo(attendues);
    }

    @Test
    void controleDecritLeGenreEtLesBornesDeChaqueCle() {
        ThemeValeurs.Controle c = ThemeValeurs.controle("couleur-primaire");
        assertThat(c.genre()).isEqualTo("couleur");
        ThemeValeurs.Controle e = ThemeValeurs.controle("taille-texte");
        assertThat(e.genre()).isEqualTo("entier");
        assertThat(e.min()).isEqualTo(12);
        assertThat(e.max()).isEqualTo(18);
        assertThat(ThemeValeurs.controle("liste-densite").genre()).isEqualTo("choix");
        assertThat(ThemeValeurs.controle("liste-densite").choix()).containsExactly("compact", "normal", "aere");
        assertThat(ThemeValeurs.controle("police").genre()).isEqualTo("police");
        assertThat(ThemeValeurs.controle("police").choix()).containsExactlyElementsOf(ThemeValeurs.policesAutorisees());
        for (String cle : ThemeValeurs.cles()) {
            assertThat(ThemeValeurs.controle(cle)).isNotNull();
        }
        assertThatThrownBy(() -> ThemeValeurs.controle("inconnue")).isInstanceOf(IllegalArgumentException.class);
    }
}
