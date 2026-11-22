package com.logicielpourtous.site.features.theme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Service de theme sur la vraie base. La table des reglages est partagee : son contenu est sauvegarde avant
 * chaque test et remis a l'identique apres ; les lignes de journal du test sont supprimees.
 */
@SpringBootTest
class ThemeServiceTest {

    private static final long ADMIN_A = 910001L;
    private static final long ADMIN_B = 910002L;

    @Autowired private ThemeParDefautSource source;
    @Autowired private ThemeReglageRepository reglages;
    @Autowired private ThemeJournalRepository journal;
    @Autowired private JdbcTemplate jdbc;

    private ThemeService service;
    private Map<String, String> sauvegarde;

    /** Source simulee dont les valeurs peuvent changer entre deux appels. */
    private static class SourceMutable implements ThemeParDefautSource {
        final Map<String, String> valeurs = new HashMap<>();
        @Override public Map<String, String> valeurs() { return valeurs; }
    }

    @BeforeEach
    void preparer() {
        sauvegarde = new HashMap<>();
        jdbc.query("SELECT cle, valeur FROM theme_reglages", rs -> { sauvegarde.put(rs.getString(1), rs.getString(2)); });
        jdbc.update("DELETE FROM theme_reglages");
        service = new ThemeService(source, reglages, journal);
    }

    @AfterEach
    void nettoyer() {
        jdbc.update("DELETE FROM theme_journal WHERE modifie_par IN (910001, 910002)");
        jdbc.update("DELETE FROM theme_reglages");
        sauvegarde.forEach((c, v) -> jdbc.update("INSERT INTO theme_reglages (cle, valeur) VALUES (?, ?)", c, v));
    }

    @Test
    void parDefautToutEstHerite() {
        Map<String, ThemeService.Etat> etat = service.reglages();
        assertThat(etat.keySet()).containsExactlyElementsOf(ThemeValeurs.cles());
        assertThat(etat.values()).allMatch(e -> e.origine() == ThemeService.Origine.HERITE);
    }

    @Test
    void definirEcritJournaliseEtPasseAModifie() {
        service.definir(ADMIN_A, "couleur-primaire", "#AABBCC");
        ThemeService.Etat e = service.reglages().get("couleur-primaire");
        assertThat(e.valeur()).isEqualTo("#aabbcc");
        assertThat(e.origine()).isEqualTo(ThemeService.Origine.MODIFIE);
        List<Map<String, Object>> lignes = jdbc.queryForList(
                "SELECT cle, ancienne_valeur, nouvelle_valeur, modifie_par FROM theme_journal WHERE modifie_par = 910001");
        assertThat(lignes).hasSize(1);
        assertThat(lignes.get(0)).containsEntry("cle", "couleur-primaire").containsEntry("ancienne_valeur", null)
                .containsEntry("nouvelle_valeur", "#aabbcc").containsEntry("modifie_par", ADMIN_A);
        service.definir(ADMIN_A, "couleur-primaire", "#112233");
        assertThat(jdbc.queryForObject(
                "SELECT ancienne_valeur FROM theme_journal WHERE nouvelle_valeur = '#112233' AND modifie_par = 910001", String.class))
                .isEqualTo("#aabbcc");
    }

    @Test
    void valeurInvalideNEcritNiReglageNiJournal() {
        assertThatThrownBy(() -> service.definir(ADMIN_A, "couleur-primaire", "red;}body{display:none"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.definir(ADMIN_A, "inconnue", "#ffffff")).isInstanceOf(IllegalArgumentException.class);
        assertThat(reglages.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM theme_journal WHERE modifie_par = 910001", Integer.class)).isZero();
    }

    @Test
    void reinitialiserRendLaValeurParDefautEtJournalise() {
        String defaut = service.reglages().get("couleur-fond").valeur();
        service.definir(ADMIN_A, "couleur-fond", "#010203");
        service.reinitialiser(ADMIN_B, "couleur-fond");
        ThemeService.Etat e = service.reglages().get("couleur-fond");
        assertThat(e.valeur()).isEqualTo(defaut);
        assertThat(e.origine()).isEqualTo(ThemeService.Origine.HERITE);
        assertThat(jdbc.queryForObject(
                "SELECT ancienne_valeur FROM theme_journal WHERE modifie_par = 910002 AND nouvelle_valeur IS NULL", String.class))
                .isEqualTo("#010203");
    }

    @Test
    void toutReinitialiserJournaliseUneLigneParReglageSupprime() {
        service.definir(ADMIN_A, "couleur-fond", "#010203");
        service.definir(ADMIN_A, "arrondi", "5");
        service.toutReinitialiser(ADMIN_B);
        assertThat(reglages.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM theme_journal WHERE modifie_par = 910002", Integer.class)).isEqualTo(2);
        assertThat(service.reglages().values()).allMatch(e -> e.origine() == ThemeService.Origine.HERITE);
    }

    @Test
    void sourceParDefautQuiChange_lesHeritesSuiventLesModifiesRestent() {
        SourceMutable simulee = new SourceMutable();
        simulee.valeurs.putAll(source.valeurs());
        ThemeService s = new ThemeService(simulee, reglages, journal);
        s.definir(ADMIN_A, "couleur-fond", "#010203");
        simulee.valeurs.put("couleur-fond", "#fefefe");
        simulee.valeurs.put("couleur-texte", "#0a0a0a");
        Map<String, ThemeService.Etat> etat = s.reglages();
        assertThat(etat.get("couleur-fond").valeur()).isEqualTo("#010203");
        assertThat(etat.get("couleur-texte").valeur()).isEqualTo("#0a0a0a");
        assertThat(etat.get("couleur-texte").origine()).isEqualTo(ThemeService.Origine.HERITE);
        assertThat(s.css()).contains("--socle-couleur-texte:#0a0a0a;").contains("--socle-couleur-fond:#010203;");
    }

    @Test
    void referenceRendueEnVar() {
        String css = service.css();
        assertThat(css).contains("--socle-topbar-fond:var(--socle-couleur-primaire);");
        assertThat(css).startsWith(":root{").endsWith("}\n");
    }

    @Test
    void referenceEffectiveSuitLaCible() {
        service.definir(ADMIN_A, "couleur-primaire", "#123456");
        ThemeService.Etat e = service.reglages().get("topbar-fond");
        assertThat(e.reference()).isEqualTo("couleur-primaire");
        assertThat(e.valeur()).isEqualTo("#123456");
    }

    private static final Pattern LIGNE = Pattern.compile("--socle-([a-z0-9-]+):[A-Za-z0-9#(),.' \\-]+;");

    @Test
    void cssNeContientQueLesVariablesDeLaListe() {
        service.definir(ADMIN_A, "couleur-primaire", "#AABBCC");
        service.definir(ADMIN_A, "police", "roboto");
        String css = service.css();
        String corps = css.substring(":root{".length(), css.length() - "}\n".length());
        List<String> cles = new ArrayList<>();
        for (String l : corps.split("\n")) {
            if (l.isEmpty()) {
                continue;
            }
            Matcher m = LIGNE.matcher(l);
            assertThat(m.matches()).as(l).isTrue();
            cles.add(m.group(1));
        }
        assertThat(cles).containsExactlyElementsOf(ThemeValeurs.cles());
        assertThat(css).doesNotContain("<").doesNotContain("url(").doesNotContain("@import").doesNotContain("/*");
    }

    @Test
    void valeurHostileEnBaseEstIgnoreeEtJamaisEmise() {
        // ligne ecrite hors du service (acces direct a la base) : le CSS n'emet jamais une valeur non validee
        jdbc.update("INSERT INTO theme_reglages (cle, valeur) VALUES ('couleur-fond', 'red;}a{x:url(1)')");
        String css = service.css();
        assertThat(css).doesNotContain("red").doesNotContain("url(").doesNotContain("}a{");
        assertThat(css).contains("--socle-couleur-fond:#");
        assertThat(service.reglages().get("couleur-fond").origine()).isEqualTo(ThemeService.Origine.HERITE);
    }

    @Test
    void aucuneValeurSaisieNePeutIntroduireDeSyntaxeCss() {
        String[] hostiles = {"#ffffff;}", "#ffffff}", "{#ffffff", "#ffffff<", "url(x)", "#fff;color:red", "5;}", "oui;}"};
        for (String h : hostiles) {
            for (String cle : List.of("couleur-primaire", "arrondi", "liste-densite", "police")) {
                assertThatThrownBy(() -> service.definir(ADMIN_A, cle, h)).isInstanceOf(IllegalArgumentException.class);
            }
        }
        String css = service.css();
        assertThat(css).doesNotContain("url(").doesNotContain("<");
        assertThat(css.chars().filter(c -> c == '{').count()).isEqualTo(1);
        assertThat(css.chars().filter(c -> c == '}').count()).isEqualTo(1);
    }

    @Test
    void sourceParDefautHostileNeProduitJamaisDeCss() {
        SourceMutable simulee = new SourceMutable();
        simulee.valeurs.putAll(source.valeurs());
        simulee.valeurs.put("couleur-fond", "red;}body{display:none");
        ThemeService s = new ThemeService(simulee, reglages, journal);
        assertThatThrownBy(s::css).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void versionCssChangeApresChaqueModification() {
        String v0 = service.versionCss();
        assertThat(service.versionCss()).isEqualTo(v0);
        service.definir(ADMIN_A, "couleur-fond", "#010203");
        String v1 = service.versionCss();
        assertThat(v1).isNotEqualTo(v0);
        service.definir(ADMIN_A, "couleur-fond", "#040506");
        String v2 = service.versionCss();
        assertThat(v2).isNotEqualTo(v1);
        service.reinitialiser(ADMIN_A, "couleur-fond");
        assertThat(service.versionCss()).isEqualTo(v0);
    }
}
