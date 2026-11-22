package com.logicielpourtous.site.features.theme;

import java.util.Map;

/** Origine des valeurs par defaut du theme (aujourd'hui un fichier livre ; plus tard, un plugin). */
public interface ThemeParDefautSource {

    /** Une valeur par cle de {@link ThemeValeurs#cles()} ; une valeur peut etre {@code ref:<cle>}. */
    Map<String, String> valeurs();
}
