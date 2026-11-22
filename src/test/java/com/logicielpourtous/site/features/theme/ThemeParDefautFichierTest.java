package com.logicielpourtous.site.features.theme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ThemeParDefautFichierTest {

    private static ThemeParDefautFichier depuis(String texte) {
        return new ThemeParDefautFichier(new ByteArrayInputStream(texte.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void fichierLivreContientToutesLesClesAvecDesValeursValides() {
        Map<String, String> v = new ThemeParDefautFichier().valeurs();
        assertThat(v.keySet()).containsExactlyInAnyOrderElementsOf(ThemeValeurs.cles());
        v.forEach((c, val) -> ThemeValeurs.validerDefaut(c, val));
    }

    private static String complet(String cle, String valeurRemplacee) {
        Map<String, String> v = new LinkedHashMap<>(new ThemeParDefautFichier().valeurs());
        if (valeurRemplacee == null) {
            v.remove(cle);
        } else {
            v.put(cle, valeurRemplacee);
        }
        StringBuilder sb = new StringBuilder();
        v.forEach((c, val) -> sb.append(c).append('=').append(val).append('\n'));
        return sb.toString();
    }

    @Test
    void defautInvalideFaitEchouerLeDemarrage() {
        assertThatThrownBy(() -> depuis(complet("couleur-fond", "red;}body{display:none")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("couleur-fond")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("body{"));
    }

    @Test
    void cleManquanteOuInconnueFaitEchouerLeDemarrage() {
        assertThatThrownBy(() -> depuis(complet("couleur-fond", null))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> depuis(complet("couleur-fond", "#ffffff") + "inconnue=#ffffff\n"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void referenceVersCleInexistanteFaitEchouerLeDemarrage() {
        assertThatThrownBy(() -> depuis(complet("topbar-fond", "ref:nexistepas"))).isInstanceOf(IllegalStateException.class);
    }
}
