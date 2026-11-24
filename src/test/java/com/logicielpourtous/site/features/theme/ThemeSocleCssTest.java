package com.logicielpourtous.site.features.theme;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/** Garde-fou de derive : socle.css et les cles du theme doivent rester alignes. */
class ThemeSocleCssTest {

    /** Les trois reglages a choix ne sont pas des variables CSS : ils sont consommes via les variables DERIVEES (ThemeValeurs.variablesDerivees). */
    private static final Set<String> CLES_SANS_USAGE_CSS = Set.of(
            "liste-densite", "liste-lignes-alternees", "fiche-statut-style");

    private static String socleCss() throws Exception {
        try (InputStream in = ThemeSocleCssTest.class.getResourceAsStream("/static/css/socle.css")) {
            assertThat(in).as("socle.css introuvable sur le classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Set<String> variablesUtilisees(String css) {
        Matcher m = Pattern.compile("var\\(--socle-([a-z0-9-]+)").matcher(css);
        Set<String> noms = new LinkedHashSet<>();
        while (m.find()) {
            noms.add(m.group(1));
        }
        return noms;
    }

    @Test
    void toutesLesVariablesUtiliseesSontDesClesDuThemeOuDesVariablesDerivees() throws Exception {
        Set<String> permises = new LinkedHashSet<>(ThemeValeurs.cles());
        permises.addAll(ThemeValeurs.nomsVariablesDerivees());
        for (String nom : variablesUtilisees(socleCss())) {
            assertThat(permises).as("variable --socle-" + nom + " utilisee dans socle.css mais inconnue du theme").contains(nom);
        }
    }

    @Test
    void chaqueCleDuThemeEstConsommeeOuExplicitementExemptee() throws Exception {
        Set<String> utilisees = variablesUtilisees(socleCss());
        for (String cle : ThemeValeurs.cles()) {
            if (!CLES_SANS_USAGE_CSS.contains(cle)) {
                assertThat(utilisees).as("la cle de theme --socle-" + cle + " n'est utilisee nulle part dans socle.css").contains(cle);
            }
        }
        for (String nom : ThemeValeurs.nomsVariablesDerivees()) {
            assertThat(utilisees).as("la variable derivee --socle-" + nom + " n'est utilisee nulle part").contains(nom);
        }
    }

    @Test
    void socleCssNeDefinitAucuneVariableDuThemeEtLesPolicesSontRelatives() throws Exception {
        String css = socleCss();
        assertThat(css).doesNotContain("--socle-couleur-primaire:").doesNotContain("--socle-primaire");
        assertThat(css).doesNotContain("url('/css/fonts/").contains("url('fonts/inter-latin-400-normal.woff2')");
    }
}
