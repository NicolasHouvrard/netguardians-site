package com.logicielpourtous.site.features.theme;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

/** Valeurs par defaut lues une fois, au demarrage, dans {@code theme/theme-defaut.properties} ; toutes sont validees. */
@Component
public class ThemeParDefautFichier implements ThemeParDefautSource {

    static final String CHEMIN = "theme/theme-defaut.properties";

    private final Map<String, String> valeurs;

    public ThemeParDefautFichier() {
        this(ouvrir());
    }

    ThemeParDefautFichier(InputStream flux) {
        Properties p = new Properties();
        try (InputStream in = flux; InputStreamReader lecteur = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            p.load(lecteur);
        } catch (IOException e) {
            throw new IllegalStateException("Lecture impossible du fichier de theme par defaut.", e);
        }
        Map<String, String> lues = new TreeMap<>();
        p.forEach((c, v) -> lues.put(String.valueOf(c), String.valueOf(v)));
        this.valeurs = ThemeValeurs.validerDefauts(lues);
    }

    private static InputStream ouvrir() {
        InputStream in = ThemeParDefautFichier.class.getClassLoader().getResourceAsStream(CHEMIN);
        if (in == null) {
            throw new IllegalStateException("Fichier de theme par defaut introuvable : " + CHEMIN);
        }
        return in;
    }

    @Override
    public Map<String, String> valeurs() {
        return valeurs;
    }
}
